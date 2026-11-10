package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.shared.Actor;
import cn.edu.campus.shared.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared persistence semantics for demo and real notification providers. */
@Service
public class NotificationInbox {
  private final JdbcTemplate db;

  public NotificationInbox(JdbcTemplate db) { this.db = db; }

  public List<Map<String, Object>> list(Actor actor) {
    return db.queryForList(
        "SELECT * FROM notifications WHERE recipient_id=? ORDER BY created_at DESC,id DESC LIMIT 100",
        actor.id()).stream().map(row -> {
          var result = view(row);
          result.put("id", s(row, "id"));
          return result;
        }).toList();
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Map<String, Object> snapshot(Actor actor) {
    var summary = db.queryForMap(
        "SELECT COUNT(CASE WHEN is_read=FALSE THEN 1 END) unread_count, MAX(id) latest_id"
            + " FROM notifications WHERE recipient_id=?", actor.id());
    return map("items", list(actor), "unreadCount", n(summary, "unread_count"),
        "latestId", summary.get("latest_id") == null ? null : s(summary, "latest_id"));
  }

  @Transactional
  public void read(Actor actor, long id, Runnable notifyClients) {
    BusinessException.allowed(db.update(
        "UPDATE notifications SET is_read=TRUE WHERE id=? AND recipient_id=?", id, actor.id()) == 1);
    afterCommit(notifyClients);
  }

  @Transactional
  public void readAll(Actor actor, long throughId, Runnable notifyClients) {
    db.update("UPDATE notifications SET is_read=TRUE WHERE recipient_id=? AND id<=? AND is_read=FALSE",
        actor.id(), throughId);
    afterCommit(notifyClients);
  }

  private void afterCommit(Runnable action) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() { action.run(); }
    });
  }
}
