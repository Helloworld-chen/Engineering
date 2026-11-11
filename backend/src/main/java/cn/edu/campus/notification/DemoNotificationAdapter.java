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

@Service
@ConditionalOnProperty(name = "market.providers.notification", havingValue = "demo")
public class DemoNotificationAdapter implements NotificationPort {
  private record Connection(String user, String session, SseEmitter emitter) {}

  private final MutationGate gate;
  private final NotificationInbox inbox;
  private final Map<String, Connection> clients = new ConcurrentHashMap<>();
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public DemoNotificationAdapter(
      JdbcTemplate db, PlatformTransactionManager manager, MutationGate gate, NotificationInbox inbox) {
    this.gate = gate;
    this.inbox = inbox;
    this.db = db;
    tx = new TransactionTemplate(manager);
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

  public SseEmitter subscribe(Actor a, String session) {
    var emitter = new SseEmitter(1800000L);
    String id = id();
    clients.put(id, new Connection(a.id(), session, emitter));
    emitter.onCompletion(() -> clients.remove(id));
    emitter.onTimeout(() -> clients.remove(id));
    emitter.onError(e -> clients.remove(id));
    try {
      emitter.send(SseEmitter.event().name("ready").data(map("connected", true)));
    } catch (Exception e) {
      clients.remove(id);
    }
    return emitter;
  }

  public void closeSession(String session) {
    clients.forEach(
        (id, c) -> {
          if (c.session.equals(session)) {
            disconnect(id, c);
          }
        });
  }

  @Scheduled(fixedDelay = 500)
  public void dispatch() {
    gate.enter();
    try {
      dispatchLocked();
    } finally {
      gate.leave();
    }
  }

  private void dispatchLocked() {
    for (var e :
        db.queryForList("SELECT * FROM outbox WHERE delivered=FALSE ORDER BY id LIMIT 100")) {
      tx.executeWithoutResult(
          s -> {
            if (!TimePort.BUSINESS_CHANGED.equals(s(e, "event_type"))) db.update(
                "INSERT IGNORE INTO notifications(id,recipient_id,aggregate_id,message,created_at)"
                    + " VALUES(?,?,?,?,?)",
                e.get("id"),
                e.get("recipient_id"),
                e.get("aggregate_id"),
                e.get("message"),
                e.get("created_at"));
            db.update("UPDATE outbox SET delivered=TRUE WHERE id=?", e.get("id"));
          });
      clients.forEach(
          (id, c) -> {
            if (c.user.equals(s(e, "recipient_id")))
              try {
                c.emitter.send(
                    SseEmitter.event()
                        .name(TimePort.BUSINESS_CHANGED.equals(s(e, "event_type")) ? "business-changed" : "changed")
                        .id(s(e, "id"))
                        .data(
                            map(
                                "aggregateId",
                                e.get("aggregate_id"),
                                "type",
                                e.get("event_type"))));
              } catch (Exception failure) {
                disconnect(id, c);
              }
          });
    }
  }

  @Scheduled(fixedDelay = 15000)
  public void heartbeat() {
    clients.forEach(
        (id, c) -> {
          try {
            c.emitter.send(SseEmitter.event().comment("heartbeat"));
          } catch (Exception e) {
            disconnect(id, c);
          }
        });
  }
}
