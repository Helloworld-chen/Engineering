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
  private final Map<String, Connection> clients = new ConcurrentHashMap<>();
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public DemoNotificationAdapter(
      JdbcTemplate db, PlatformTransactionManager manager, MutationGate gate) {
    this.gate = gate;
    this.db = db;
    tx = new TransactionTemplate(manager);
  }

  public List<Map<String, Object>> list(Actor a) {
    return db
        .queryForList(
            "SELECT * FROM notifications WHERE recipient_id=? ORDER BY created_at DESC,id DESC"
                + " LIMIT 100",
            a.id())
        .stream()
        .map(
            r -> {
              var m = view(r);
              m.put("id", s(r, "id"));
              return m;
            })
        .toList();
  }

  public void read(Actor a, long id) {
    BusinessException.allowed(
        db.update("UPDATE notifications SET is_read=TRUE WHERE id=? AND recipient_id=?", id, a.id())
            == 1);
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
            c.emitter.complete();
            clients.remove(id);
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
            db.update(
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
                        .name("changed")
                        .id(s(e, "id"))
                        .data(
                            map(
                                "aggregateId",
                                e.get("aggregate_id"),
                                "type",
                                e.get("event_type"))));
              } catch (Exception failure) {
                c.emitter.complete();
                clients.remove(id);
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
            clients.remove(id);
            c.emitter.complete();
          }
        });
  }
}
