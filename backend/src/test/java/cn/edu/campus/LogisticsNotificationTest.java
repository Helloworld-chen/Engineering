package cn.edu.campus;

import static org.assertj.core.api.Assertions.*;
import cn.edu.campus.notification.*;
import cn.edu.campus.trade.TradeService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class LogisticsNotificationTest extends WorkflowTestSupport {
  @Autowired NotificationPort notifications;
  @Autowired TimePort time;
  @Autowired TaskRuntimePort runtime;
  @Autowired TradeService trade;
  @Autowired PlatformTransactionManager transactions;

  @Override void advance(long millis) throws Exception {
    db.update("UPDATE clock_state SET frozen_at=frozen_at+? WHERE id=1", millis);
    runtime.getClass().getMethod("drain").invoke(runtime);
  }

  void dispatch() throws Exception { notifications.getClass().getMethod("dispatch").invoke(notifications); }

  void recipients(String order, String type, String message, String expected) {
    assertThat(db.queryForList("SELECT recipient_id FROM outbox WHERE aggregate_id=? AND event_type=? AND message=?", String.class, order, type, message))
        .containsExactly(expected);
  }

  @Test void outboundMessagesOnlyReachBuyerAndSellerGetsSilentRefresh() throws Exception {
    var buyer = new Browser("buyer"); var seller = new Browser("seller");
    String order = paid(buyer); var input = evidence(false); String key = UUID.randomUUID().toString();
    seller.call("POST", "/orders/" + order + "/shipment", input, key, 200);
    seller.call("POST", "/orders/" + order + "/shipment", input, key, 200);
    seller.post("/orders/" + order + "/shipment", input);
    recipients(order, "SHIPPED", "卖家已发货，10秒后自动送达", "buyer");
    advance(10000);
    new TransactionTemplate(transactions).executeWithoutResult(s -> trade.deadline(order, "DELIVERY", 0, time.now()));
    recipients(order, "DELIVERED", "商品已送达，48小时内可确认收货或申请售后", "buyer");
    assertThat(db.queryForList("SELECT recipient_id FROM outbox WHERE aggregate_id=? AND event_type='BUSINESS_CHANGED'", String.class, order)).containsExactly("seller", "seller");
    dispatch(); dispatch();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications WHERE aggregate_id=? AND message IN (?,?) AND recipient_id='buyer'", Integer.class, order, "卖家已发货，10秒后自动送达", "商品已送达，48小时内可确认收货或申请售后")).isEqualTo(2);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications n JOIN outbox e ON e.id=n.id WHERE e.event_type='BUSINESS_CHANGED'", Integer.class)).isZero();
  }

  @Test void returnMessagesOnlyReachSellerAndBuyerGetsSilentRefresh() throws Exception {
    var buyer = new Browser("buyer"); var seller = new Browser("seller");
    String order = paid(buyer);
    seller.post("/orders/" + order + "/shipment", evidence(false)); advance(10000);
    String c = (String) buyer.post("/cases", Map.of("orderId", order, "kind", "COMPLAINT", "reason", "商品损坏")).get("id");
    new Browser("support").post("/cases/" + c + "/actions", Map.of("action", "APPROVE_RETURN", "reason", "支持退货"));
    var input = evidence(true); String key = UUID.randomUUID().toString();
    buyer.call("POST", "/orders/" + order + "/return-shipment", input, key, 200);
    buyer.call("POST", "/orders/" + order + "/return-shipment", input, key, 200);
    buyer.post("/orders/" + order + "/return-shipment", input);
    recipients(order, "SHIPPED", "买家已寄回商品，10秒后自动送达", "seller");
    advance(10000);
    new TransactionTemplate(transactions).executeWithoutResult(s -> trade.deadline(order, "RETURN_DELIVERY", 0, time.now()));
    recipients(order, "DELIVERED", "退货已送达，卖家有24小时验收", "seller");
    assertThat(db.queryForList("SELECT recipient_id FROM outbox WHERE aggregate_id=? AND event_type='BUSINESS_CHANGED' AND recipient_id='buyer'", String.class, order)).hasSize(2);
    dispatch(); dispatch();
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications WHERE aggregate_id=? AND message IN (?,?) AND recipient_id='seller'", Integer.class, order, "买家已寄回商品，10秒后自动送达", "退货已送达，卖家有24小时验收")).isEqualTo(2);
  }

  @Test void silentEventNeverCreatesInboxMessageAndRollsBackWithBusinessTransaction() throws Exception {
    var tx = new TransactionTemplate(transactions);
    tx.executeWithoutResult(s -> { time.emit("seller", "order", "BUSINESS_CHANGED", ""); s.setRollbackOnly(); });
    assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isZero();
    tx.executeWithoutResult(s -> time.emit("seller", "order", "BUSINESS_CHANGED", ""));
    dispatch(); dispatch();
    assertThat(new Browser("seller").call("GET", "/notifications", null, null, 200)).containsEntry("unreadCount", 0).containsEntry("latestId", null);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox WHERE delivered=TRUE", Integer.class)).isEqualTo(1);
  }

  @Test void migrationRemovesOnlyProvableWrongRecipientsAndIsRepeatable() throws Exception {
    String order = paid(new Browser("buyer"));
    db.update("DELETE FROM outbox");
    String[] messages = {"卖家已发货，10秒后自动送达", "商品已送达，48小时内可确认收货或申请售后", "买家已寄回商品，10秒后自动送达", "退货已送达，卖家有24小时验收"};
    var wrong = new ArrayList<Long>(); var retained = new ArrayList<Long>();
    long next = 1000000;
    for (int i=0;i<4;i++) {
      String type = i%2==0 ? "SHIPPED" : "DELIVERED", user = i<2 ? "seller" : "buyer", correct = i<2 ? "buyer" : "seller";
      for (int state=0;state<3;state++) {
        long id = next++; wrong.add(id); legacy(id, order, user, type, messages[i], state!=2, state==1);
      }
      long id = next++; retained.add(id); legacy(id, order, correct, type, messages[i], true, true);
      id = next++; retained.add(id); legacy(id, order, "other", type, messages[i], true, false);
      id = next++; retained.add(id); legacy(id, order, user, "OTHER", messages[i], true, false);
      id = next++; retained.add(id); legacy(id, order, user, type, messages[i]+"（不同文本）", true, false);
    }
    legacy(next, "missing-order", "seller", "SHIPPED", messages[0], true, false); retained.add(next++);
    // Mismatched inbox metadata cannot be proved to belong to the legacy event.
    legacy(next, order, "seller", "SHIPPED", messages[0], true, false);
    db.update("UPDATE notifications SET aggregate_id='unknown' WHERE id=?", next); retained.add(next);
    var before = db.queryForList("SELECT * FROM notifications WHERE id IN ("+String.join(",",retained.stream().map(String::valueOf).toList())+") ORDER BY id");
    var migration = new ClassPathResource("db/migration/V2__logistics_notification_recipients.sql");
    assertThat(migration.exists()).isTrue();
    try(var connection=db.getDataSource().getConnection()) { ScriptUtils.executeSqlScript(connection,migration); ScriptUtils.executeSqlScript(connection,migration); }
    assertThat(db.queryForList("SELECT id FROM notifications ORDER BY id",Long.class)).containsExactlyElementsOf(retained);
    assertThat(db.queryForList("SELECT * FROM notifications ORDER BY id")).isEqualTo(before);
    for (long id:wrong) assertThat(db.queryForObject("SELECT event_type FROM outbox WHERE id=?",String.class,id)).isEqualTo("BUSINESS_CHANGED");
    dispatch(); dispatch();
    assertThat(db.queryForList("SELECT id FROM notifications ORDER BY id",Long.class)).containsExactlyElementsOf(retained);
  }

  void legacy(long id,String order,String user,String type,String message,boolean delivered,boolean read) {
    db.update("INSERT INTO outbox(id,recipient_id,aggregate_id,event_type,message,created_at,delivered) VALUES(?,?,?,?,?,0,?)",id,user,order,type,message,delivered);
    if(delivered) db.update("INSERT INTO notifications(id,recipient_id,aggregate_id,message,created_at,is_read) VALUES(?,?,?,?,0,?)",id,user,order,message,read);
  }
}
