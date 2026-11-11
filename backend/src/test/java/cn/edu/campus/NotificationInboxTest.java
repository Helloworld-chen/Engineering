package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import cn.edu.campus.notification.NotificationInbox;
import cn.edu.campus.shared.Actor;

class NotificationInboxTest extends ApiTestSupport {
  @Autowired NotificationInbox inbox;
  @Autowired PlatformTransactionManager transactions;
  @Autowired cn.edu.campus.notification.NotificationPort notifications;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"read", "read-all", "close", "heartbeat", "dispatch"})
  void disconnectedStreamCleanupDoesNotFailCommittedOperationsOrOtherClients(String operation) throws Exception {
    var seller = new Browser("seller");
    message(1, "seller");
    var broken = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter() {
      @Override public void send(SseEventBuilder event) throws java.io.IOException {
        throw new java.io.IOException("peer disconnected");
      }
      @Override public void complete() {
        throw new IllegalStateException("AsyncContext already invalid");
      }
    };
    var deliveries = new AtomicInteger();
    var healthy = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter() {
      @Override public void send(SseEventBuilder event) { deliveries.incrementAndGet(); }
      @Override public void complete() { deliveries.incrementAndGet(); }
    };
    // Inject the deterministic disconnected servlet boundary; exercise real provider and HTTP writes.
    var type = Class.forName(notifications.getClass().getName() + "$Connection");
    var constructor = type.getDeclaredConstructor(String.class, String.class,
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class);
    constructor.setAccessible(true);
    var clients = (Map<String,Object>) org.springframework.test.util.ReflectionTestUtils.getField(notifications, "clients");
    clients.put("pr8-broken", constructor.newInstance("seller", "race-session", broken));
    clients.put("pr8-healthy", constructor.newInstance("seller", "race-session", healthy));
    try {
      switch (operation) {
        case "read" -> seller.post("/notifications/1/read", Map.of());
        case "read-all" -> seller.post("/notifications/read-all", Map.of("throughId", "1"));
        case "close" -> notifications.closeSession("race-session");
        case "heartbeat" -> notifications.getClass().getMethod("heartbeat").invoke(notifications);
        case "dispatch" -> {
          db.update("INSERT INTO outbox(id,recipient_id,aggregate_id,event_type,message,created_at)"
              + " VALUES(2,'seller','order','TEST','new message',1)");
          notifications.getClass().getMethod("dispatch").invoke(notifications);
          assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications WHERE id=2", Integer.class)).isOne();
          assertThat(db.queryForObject("SELECT delivered FROM outbox WHERE id=2", Boolean.class)).isTrue();
        }
      }
      assertThat(clients).doesNotContainKey("pr8-broken");
      assertThat(deliveries.get()).isOne();
      if (operation.startsWith("read"))
        assertThat(db.queryForObject("SELECT is_read FROM notifications WHERE id=1", Boolean.class)).isTrue();
    } finally {
      clients.remove("pr8-broken");clients.remove("pr8-healthy");
    }
  }

  void message(long id, String user) {
    db.update("INSERT INTO notifications(id,recipient_id,aggregate_id,message,created_at) VALUES(?,?,?,?,?)",
        id, user, "order", "新的交易消息", id);
  }

  @Test
  void snapshotCountsAllUnreadBeyondRecentHundredAndKeepsIdsAsStrings() throws Exception {
    var seller = new Browser("seller");
    for (int i = 1; i <= 105; i++) message(i, "seller");
    message(9007199254740993L, "buyer");
    var snapshot = seller.call("GET", "/notifications", null, null, 200);
    assertThat(snapshot).containsEntry("unreadCount", 105).containsEntry("latestId", "105");
    assertThat((List<?>) snapshot.get("items")).hasSize(100);
    var buyer = new Browser("buyer");
    assertThat(buyer.call("GET", "/notifications", null, null, 200))
        .containsEntry("latestId", "9007199254740993").containsEntry("unreadCount", 1);
    var other = new Browser("other");
    assertThat(other.call("GET", "/notifications", null, null, 200))
        .containsEntry("latestId", null).containsEntry("unreadCount", 0);
  }

  @Test
  void readAllIsBoundedBySnapshotAndOwnerAndReplayDoesNotConsumeLaterMessages() throws Exception {
    var seller = new Browser("seller");
    message(1, "seller"); message(2, "buyer"); message(3, "seller");
    var before = seller.call("GET", "/notifications", null, null, 200);
    message(4, "seller");
    String key = UUID.randomUUID().toString();
    var input = Map.of("throughId", "3");
    seller.call("POST", "/notifications/read-all", input, key, 200);
    message(5, "seller");
    seller.call("POST", "/notifications/read-all", input, key, 200);
    assertThat(before.get("latestId")).isEqualTo("3");
    assertThat(db.queryForList("SELECT id FROM notifications WHERE is_read=FALSE ORDER BY id", Long.class))
        .containsExactly(2L, 4L, 5L);
    assertThat(seller.call("GET", "/notifications", null, null, 200)).containsEntry("unreadCount", 2);
    seller.call("POST", "/notifications/read-all", Map.of("throughId", "5"), key, 409);
  }

  @Test
  void singleReadRemainsOwnerScopedAndAllReadRequiresValidBoundAndSession() throws Exception {
    var seller = new Browser("seller");
    message(1, "seller"); message(2, "buyer");
    seller.post("/notifications/1/read", Map.of());
    seller.call("POST", "/notifications/2/read", Map.of(), UUID.randomUUID().toString(), 403);
    for (String invalid : List.of("0", "-1", "oops", "9223372036854775808")) {
      seller.call("POST", "/notifications/read-all", Map.of("throughId", invalid), UUID.randomUUID().toString(), 400);
    }
    seller.call("POST", "/notifications/read-all", Map.of(), UUID.randomUUID().toString(), 400);
    new Browser(null).call("POST", "/notifications/read-all", Map.of("throughId", "2"), UUID.randomUUID().toString(), 401);
    seller.csrf = "invalid";
    seller.call("POST", "/notifications/read-all", Map.of("throughId", "2"), UUID.randomUUID().toString(), 403);
    assertThat(db.queryForList("SELECT id FROM notifications WHERE is_read=FALSE", Long.class)).containsExactly(2L);
  }

  @Test
  void readSignalsWaitForCommitAndNeverEscapeRollback() {
    message(1, "seller");
    var calls = new AtomicInteger();
    var actor = new Actor("seller", "seller", "林同学", "USER");
    var transaction = new TransactionTemplate(transactions);
    transaction.executeWithoutResult(status -> {
      inbox.read(actor, 1, calls::incrementAndGet);
      assertThat(calls.get()).isZero();
      status.setRollbackOnly();
    });
    assertThat(calls.get()).isZero();
    assertThat(db.queryForObject("SELECT is_read FROM notifications WHERE id=1", Boolean.class)).isFalse();
    transaction.executeWithoutResult(status -> {
      inbox.readAll(actor, 1, calls::incrementAndGet);
      assertThat(calls.get()).isZero();
    });
    assertThat(calls.get()).isEqualTo(1);
    assertThat(db.queryForObject("SELECT is_read FROM notifications WHERE id=1", Boolean.class)).isTrue();
  }
}
