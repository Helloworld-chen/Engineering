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

/**
 * M06 商品审核与举报处理。
 *
 * <p>规则来源：个人需求 Z06（审核）、Z07（举报）、Z08（处置）、Z09（权限与追踪）、NFR-Z05（可追溯）。
 *
 * <p>与旧实现的差异：
 *
 * <ul>
 *   <li>审核结论记录 {@code reviewed_version}，把结论钉在管理员实际看到的版本上。
 *   <li>举报只允许针对公开在售商品，且不能举报本人商品；同一用户对同一商品只保留一条待处理举报。
 *   <li>处置结果识别 VIOLATION / DISMISSED，违规时经 M04 的 {@code updateSale} 下架；已锁定或已售出的商品不改写状态。
 *   <li>审核、举报、处置均写 {@code audit_log}，满足按对象追溯的要求。
 * </ul>
 *
 * <p>处置结果的判定口径：正文含「不成立」或等于 {@code DISMISSED} 视为驳回；含「确认违规」或等于 {@code VIOLATION}
 * 视为违规；其他文本按"仅记录结论"处理，不下架。这样既不改变既有接口的 {@code result} 字段语义，也能表达违规处置。
 */
@Service
@Transactional
@ConditionalOnProperty(name = "market.providers.moderation", havingValue = "demo")
public class DemoModerationAdapter implements ModerationPort {

  static final int MAX_REASON = 2000;
  static final int MAX_LIST = 200;
  static final String DECISION_VIOLATION = "VIOLATION";
  static final String DECISION_DISMISSED = "DISMISSED";

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
        .queryForList(
            "SELECT * FROM products WHERE audit_status='PENDING' ORDER BY created_at,id LIMIT ?",
            MAX_LIST)
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
    allowed(a.is("ADMIN"));
    String text = requireReason(reason, "请填写审核理由");
    // 版本栅栏、状态校验与通知由 M04 完成，本方法只补审核记录与审计
    products.moderate(a, id, decision, text, version);
    db.update(
        "INSERT INTO moderation_reviews(id,product_id,actor_id,decision,reason,created_at,reviewed_version)"
            + " VALUES(?,?,?,?,?,?,?)",
        id(),
        id,
        a.id(),
        decision,
        text,
        time.now(),
        version);
    audit(a, "PRODUCT_REVIEW", id);
    return products.get(id, a);
  }

  public Map<String, Object> report(Actor a, String id, String reason) {
    String text = requireReason(reason, "请填写举报理由");
    // 先锁商品：同一商品的举报在此串行化，避免并发下重复建单
    var rows = db.queryForList("SELECT * FROM products WHERE id=? FOR UPDATE", id);
    require(!rows.isEmpty(), "NOT_FOUND", "商品不存在");
    var p = rows.getFirst();
    require(
        "APPROVED".equals(s(p, "audit_status"))
            && Set.of("AVAILABLE", "LOCKED").contains(s(p, "sale_status")),
        "PRODUCT_NOT_PUBLIC",
        "只能举报公开在售的商品");
    require(!a.id().equals(s(p, "seller_id")), "SELF_REPORT", "不能举报本人发布的商品");

    var existing =
        db.queryForList(
            "SELECT id FROM reports WHERE reporter_id=? AND product_id=? AND status='PENDING'",
            a.id(),
            id);
    if (!existing.isEmpty()) {
      return map("id", existing.getFirst().get("id"), "status", "PENDING", "duplicated", true);
    }

    String reportId = id();
    db.update(
        "INSERT INTO reports(id,product_id,reporter_id,reason,status,result,created_at)"
            + " VALUES(?,?,?,?,'PENDING',NULL,?)",
        reportId,
        id,
        a.id(),
        text,
        time.now());
    audit(a, "REPORT_CREATE", reportId);
    return map("id", reportId, "status", "PENDING", "duplicated", false);
  }

  public List<Map<String, Object>> reports(Actor a) {
    boolean admin = a.is("ADMIN");
    // 管理员侧带出商品发布者，供前端跳转其资料页；普通用户只查本人举报
    String sql =
        (admin
                ? "SELECT r.*,p.seller_id FROM reports r LEFT JOIN products p ON p.id=r.product_id"
                : "SELECT r.* FROM reports r WHERE r.reporter_id=?")
            + " ORDER BY r.created_at DESC,r.id DESC LIMIT ?";
    Object[] args = admin ? new Object[] {MAX_LIST} : new Object[] {a.id(), MAX_LIST};
    return db.queryForList(sql, args).stream().map(Rows::view).toList();
  }

  public Map<String, Object> resolve(Actor a, String id, String result) {
    allowed(a.is("ADMIN"));
    String text = requireReason(result, "请填写处理结果");

    // 加锁顺序遵循 INTEGRATION 的「商品 → ...」：先无锁读出商品号，再先锁商品、后锁举报，
    // 与 report() 的「先锁商品」保持一致，避免两个入口反向加锁导致死锁。
    var head = db.queryForList("SELECT product_id FROM reports WHERE id=?", id);
    require(!head.isEmpty(), "NOT_FOUND", "举报不存在");
    String productId = s(head.getFirst(), "product_id");

    var productRows = productId.isEmpty()
        ? List.<Map<String, Object>>of()
        : db.queryForList("SELECT * FROM products WHERE id=? FOR UPDATE", productId);

    var rows = db.queryForList("SELECT * FROM reports WHERE id=? FOR UPDATE", id);
    require(!rows.isEmpty(), "NOT_FOUND", "举报不存在");
    var report = rows.getFirst();
    require("PENDING".equals(s(report, "status")), "INVALID_STATE", "举报已处理");

    String decision = decisionOf(text);
    boolean takedown = false;
    if (DECISION_VIOLATION.equals(decision) && !productRows.isEmpty()) {
      var product = productRows.getFirst();
      String sale = s(product, "sale_status");
      String audit = s(product, "audit_status");
      if ("AVAILABLE".equals(sale) && "APPROVED".equals(audit)) {
        // M04 的下架接口：校验管理员身份与商品版本，只把在售商品改为已下架，
        // 同时把审核结论置为已驳回，卖家须改内容并重新审核通过后才能恢复销售。
        products.takedown(a, productId, text, n(product, "version"));
        takedown = true;
      } else if ("PENDING".equals(audit)) {
        // 卖家在处置前改过内容，商品已回到待审核，直接驳回即可
        products.moderate(a, productId, "REJECTED", text, n(product, "version"));
        takedown = true;
      }
      // LOCKED：有进行中的订单，保留原状态并继续履约；SOLD：终态；OFF_SHELF / REJECTED：已不在售
    }

    // 结论随处置一并落库，列表与详情据此展示「确认违规 / 不成立」，不必再解析自由文本
    db.update(
        "UPDATE reports SET status='RESOLVED',result=?,decision=? WHERE id=?",
        text,
        decision,
        id);
    audit(a, "REPORT_RESOLVE", id);
    time.emit(
        s(report, "reporter_id"),
        s(report, "product_id"),
        "REPORT_RESOLVED",
        takedown ? "举报属实，商品已下架" : "举报已处理，请查看处理结果");
    return map(
        "id", id,
        "status", "RESOLVED",
        "result", text,
        "decision", decision,
        "productTakedown", takedown);
  }

  /** 从处置文本推断结论；无法判断时返回 null，表示只记录结论、不触发下架。 */
  static String decisionOf(String text) {
    String t = text == null ? "" : text.trim();
    if (t.equalsIgnoreCase(DECISION_DISMISSED) || t.contains("不成立") || t.contains("驳回")) {
      return DECISION_DISMISSED;
    }
    if (t.equalsIgnoreCase(DECISION_VIOLATION) || t.contains("确认违规") || t.contains("下架")) {
      return DECISION_VIOLATION;
    }
    return null;
  }

  private static String requireReason(String reason, String message) {
    require(
        reason != null && !reason.isBlank() && reason.trim().length() <= MAX_REASON,
        "INVALID_REASON",
        message);
    return reason.trim();
  }

  private void audit(Actor a, String action, String aggregateId) {
    db.update(
        "INSERT INTO audit_log(actor_id,action,aggregate_id,created_at) VALUES(?,?,?,?)",
        a.id(),
        action,
        aggregateId,
        time.now());
  }
}
