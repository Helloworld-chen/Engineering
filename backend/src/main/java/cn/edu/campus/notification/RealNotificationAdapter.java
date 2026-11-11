package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.shared.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * M08 站内通知的正式实现。
 *
 * <p>业务事务写入的待投递事件（outbox）由本类定时扫描：先落库为站内通知，再通过 SSE
 * 提示在线页面重新查询权威状态。投递失败独立于已提交的业务，不会回滚交易。
 * 通知与事件通过事件编号保证幂等，重复投递不会生成重复通知。
 */
@Service
@ConditionalOnProperty(name = "market.providers.notification", havingValue = "real")
public class RealNotificationAdapter implements NotificationPort {
  private record Connection(String user, String session, SseEmitter emitter) {}

  private final MutationGate gate;
  private final NotificationInbox inbox;
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Map<String, Connection> clients = new ConcurrentHashMap<>();

  public RealNotificationAdapter(
      JdbcTemplate db, PlatformTransactionManager manager, MutationGate gate, NotificationInbox inbox) {
    this.gate = gate;
    this.inbox = inbox;
    this.db = db;
    this.tx = new TransactionTemplate(manager);
  }

  @Override
  public List<Map<String, Object>> list(Actor actor) { return inbox.list(actor); }

  @Override
  public Map<String, Object> snapshot(Actor actor) { return inbox.snapshot(actor); }

  @Override
  public void read(Actor actor, long id) {
    inbox.read(actor, id, () -> notifyRead(actor));
  }

  @Override
  public void readAll(Actor actor, long throughId) {
    inbox.readAll(actor, throughId, () -> notifyRead(actor));
  }

  private void disconnect(String id, Connection connection) {
    clients.remove(id, connection);
    try {
      connection.emitter().complete();
    } catch (IllegalStateException alreadyClosed) {
      // A disconnected servlet may invalidate AsyncContext before cleanup runs.
      // The connection is already removed; never fail a committed write or other clients.
    }
  }

  private void notifyRead(Actor actor) {
    clients.forEach((id, connection) -> {
      if (!connection.user().equals(actor.id())) return;
      try {
        connection.emitter().send(SseEmitter.event().name("notifications-read").data(map("success", true)));
      } catch (Exception failure) {
        disconnect(id, connection);
      }
    });
  }

  @Override
  public SseEmitter subscribe(Actor actor, String session) {
    var emitter = new SseEmitter(1_800_000L);
    String connectionId = id();
    clients.put(connectionId, new Connection(actor.id(), session, emitter));
    emitter.onCompletion(() -> clients.remove(connectionId));
    emitter.onTimeout(() -> clients.remove(connectionId));
    emitter.onError(e -> clients.remove(connectionId));
    try {
      emitter.send(SseEmitter.event().name("ready").data(map("connected", true)));
    } catch (Exception e) {
      clients.remove(connectionId);
    }
    return emitter;
  }

  @Override
  public void closeSession(String session) {
    clients.forEach(
        (id, connection) -> {
          if (connection.session.equals(session)) {
            disconnect(id, connection);
          }
        });
  }

  /** 定时分发已提交的待投递事件。 */
  @Scheduled(fixedDelay = 500)
  public void dispatch() {
    gate.enter();
    try {
      dispatchCommittedEvents();
    } finally {
      gate.leave();
    }
  }

  private void dispatchCommittedEvents() {
    for (var event :
        db.queryForList("SELECT * FROM outbox WHERE delivered=FALSE ORDER BY id LIMIT 100")) {
      tx.executeWithoutResult(
          status -> {
            if (!TimePort.BUSINESS_CHANGED.equals(s(event, "event_type"))) db.update(
                "INSERT IGNORE INTO notifications(id,recipient_id,aggregate_id,message,created_at)"
                    + " VALUES(?,?,?,?,?)",
                event.get("id"),
                event.get("recipient_id"),
                event.get("aggregate_id"),
                event.get("message"),
                event.get("created_at"));
            db.update("UPDATE outbox SET delivered=TRUE WHERE id=?", event.get("id"));
          });
      pushToOnlineClients(event);
    }
  }

  private void pushToOnlineClients(Map<String, Object> event) {
    clients.forEach(
        (id, connection) -> {
          if (!connection.user.equals(s(event, "recipient_id"))) return;
          try {
            connection.emitter.send(
                SseEmitter.event()
                    .name(TimePort.BUSINESS_CHANGED.equals(s(event, "event_type")) ? "business-changed" : "changed")
                    .id(s(event, "id"))
                    .data(
                        map(
                            "aggregateId", event.get("aggregate_id"),
                            "type", event.get("event_type"))));
          } catch (Exception e) {
            disconnect(id, connection);
          }
        });
  }

  /** 心跳，保持长连接可用。 */
  @Scheduled(fixedDelay = 15_000)
  public void heartbeat() {
    clients.forEach(
        (id, connection) -> {
          try {
            connection.emitter.send(SseEmitter.event().comment("heartbeat"));
          } catch (Exception e) {
            disconnect(id, connection);
          }
        });
  }
}
