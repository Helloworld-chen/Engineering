package cn.edu.campus.moderation;

import static cn.edu.campus.shared.BusinessException.*;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.notification.TimePort;
import cn.edu.campus.product.ProductPort;
import cn.edu.campus.shared.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@ConditionalOnProperty(name = "market.providers.moderation", havingValue = "demo")
public class DemoModerationAdapter implements ModerationPort {
  private final JdbcTemplate db;
  private final ProductPort products;
  private final TimePort time;

  public DemoModerationAdapter(JdbcTemplate db, ProductPort p, TimePort t) {
    this.db = db;
    products = p;
    time = t;
  }

  public List<Map<String, Object>> pending(Actor a) {
    allowed(a.is("ADMIN"));
    return db
        .queryForList("SELECT * FROM products WHERE audit_status='PENDING' ORDER BY created_at")
        .stream()
        .map(
            r -> {
              var v = view(r);
              v.put("images", strings(r.get("images")));
              return v;
            })
        .toList();
  }

  public Map<String, Object> review(
      Actor a, String id, String decision, String reason, long version) {
    require(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "INVALID_REASON",
        "请填写审核理由");
    products.moderate(a, id, decision, reason, version);
    db.update(
        "INSERT INTO moderation_reviews VALUES(?,?,?,?,?,?)",
        id(),
        id,
        a.id(),
        decision,
        reason,
        time.now());
    return products.get(id, a);
  }

  public Map<String, Object> report(Actor a, String id, String reason) {
    products.get(id, a);
    require(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "INVALID_REASON",
        "请填写举报理由");
    String r = id();
    db.update(
        "INSERT INTO reports VALUES(?,?,?,?,'PENDING',NULL,?)", r, id, a.id(), reason, time.now());
    return map("id", r, "status", "PENDING");
  }

  public List<Map<String, Object>> reports(Actor a) {
    return db
        .queryForList(
            (a.is("ADMIN")
                    ? "SELECT r.*,p.seller_id FROM reports r LEFT JOIN products p ON p.id=r.product_id"
                    : "SELECT r.* FROM reports r WHERE r.reporter_id=?")
                + " ORDER BY r.created_at DESC",
            a.is("ADMIN") ? new Object[] {} : new Object[] {a.id()})
        .stream()
        .map(Rows::view)
        .toList();
  }

  public Map<String, Object> resolve(Actor a, String id, String result) {
    allowed(a.is("ADMIN"));
    require(
        result != null && !result.isBlank() && result.length() <= 2000,
        "INVALID_REASON",
        "请填写处理结果");
    var rows = db.queryForList("SELECT * FROM reports WHERE id=? FOR UPDATE", id);
    require(!rows.isEmpty(), "NOT_FOUND", "举报不存在");
    var r = rows.getFirst();
    require("PENDING".equals(s(r, "status")), "INVALID_STATE", "举报已处理");
    db.update("UPDATE reports SET status='RESOLVED',result=? WHERE id=?", result, id);
    time.emit(s(r, "reporter_id"), s(r, "product_id"), "REPORT_RESOLVED", "举报已处理，请查看处理结果");
    return map("id", id, "status", "RESOLVED", "result", result);
  }
}
