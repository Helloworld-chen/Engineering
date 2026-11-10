package cn.edu.campus.identity;

import static cn.edu.campus.shared.BusinessException.allowed;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.product.ProductPort;
import cn.edu.campus.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Authorizes each profile read against a single business record. */
@Component
class ProfileContext {
  private final JdbcTemplate db;
  private final ProductPort products;

  ProfileContext(JdbcTemplate db, ProductPort products) {
    this.db = db;
    this.products = products;
  }

  Map<String, Object> authorize(Actor viewer, String user, String type, String id, boolean includeDetails) {
    if (viewer.is("USER") && viewer.id().equals(user)) return null;
    allowed(type != null && id != null && !id.isBlank());
    return switch (type) {
      case "product" -> product(viewer, user, id, includeDetails);
      case "report" -> report(viewer, user, id, includeDetails);
      case "case", "order" -> order(viewer, user, type, id, includeDetails);
      default -> throw new BusinessException("FORBIDDEN", "无权访问该用户资料", 403);
    };
  }

  private Map<String, Object> product(Actor viewer, String user, String id, boolean includeDetails) {
    allowed(viewer.is("USER") || viewer.is("ADMIN"));
    var product = products.get(id, viewer);
    allowed(user.equals(s(product, "sellerId")));
    if (viewer.is("USER") || !includeDetails) return null;
    String productId = s(product, "id");
    var details = new ArrayList<>(List.of(
        detail("相关身份", "商品发布者"),
        detail("审核状态", moderationState(s(product, "auditStatus")))));
    for (var review : db.queryForList(
        "SELECT decision,reason,created_at FROM moderation_reviews WHERE product_id=?"
            + " ORDER BY created_at DESC,id DESC", productId)) {
      details.add(history("审核记录", moderationState(s(review, "decision")), review));
    }
    return context("product", productId, s(product, "title"), details,
        List.of(link("查看商品审核", "/moderation/" + productId)));
  }

  private Map<String, Object> report(Actor viewer, String user, String id, boolean includeDetails) {
    allowed(viewer.is("ADMIN"));
    var report = find(
        "SELECT r.id,r.reporter_id,r.status,r.reason,r.result,p.seller_id,p.title FROM reports r"
            + " LEFT JOIN products p ON p.id=r.product_id WHERE r.id=?", id);
    boolean reporter = user.equals(s(report, "reporter_id"));
    allowed(reporter || user.equals(s(report, "seller_id")));
    if (!includeDetails) return null;
    var details = new ArrayList<>(List.of(
        detail("相关身份", reporter ? "举报人" : "商品发布者"),
        detail("举报状态", "RESOLVED".equals(s(report, "status")) ? "已处理" : "待处理"),
        detail("举报理由", s(report, "reason"))));
    if (report.get("result") != null) details.add(detail("处理结果", s(report, "result")));
    String reportId = s(report, "id");
    String reportPath = UriComponentsBuilder.fromPath("/reports")
        .queryParam("status", "ALL").queryParam("q", "{reportId}")
        .encode().buildAndExpand(reportId).toUriString();
    return context("report", reportId, s(report, "title").isBlank() ? "商品举报" : s(report, "title"),
        details, List.of(link("查看举报记录", reportPath)));
  }

  private Map<String, Object> order(Actor viewer, String user, String type, String id, boolean includeDetails) {
    allowed(viewer.is("USER") || viewer.is("SUPPORT"));
    String fields = "o.id order_id,o.buyer_id,o.seller_id,p.title";
    boolean caseContext = type.equals("case");
    var row = caseContext
        ? find("SELECT " + fields + ",c.id case_id FROM cases c JOIN orders o ON o.id=c.order_id"
            + " JOIN products p ON p.id=o.product_id WHERE c.id=?", id)
        : find("SELECT " + fields + " FROM orders o JOIN products p ON p.id=o.product_id"
            + " WHERE o.id=?", id);
    String buyer = s(row, "buyer_id");
    String seller = s(row, "seller_id");
    allowed(user.equals(buyer) || user.equals(seller));
    if (viewer.is("USER")) {
      allowed(viewer.id().equals(buyer) || viewer.id().equals(seller));
      return null;
    }
    String order = s(row, "order_id");
    String caseFields = "SELECT id,kind,reason,status,decision,decision_reason FROM cases";
    var current = caseContext
        ? find(caseFields + " WHERE id=?", s(row, "case_id"))
        : find(caseFields + " WHERE order_id=? ORDER BY created_at DESC,id DESC LIMIT 1", order);
    if (!includeDetails) return null;
    String caseId = s(current, "id");
    var details = new ArrayList<>(List.of(
        detail("相关身份", user.equals(buyer) ? "买家" : "卖家"),
        detail("订单编号", order),
        detail("售后单编号", caseId),
        detail("申请类型", "REFUND".equals(s(current, "kind")) ? "退款" : "投诉"),
        detail("售后状态", caseState(s(current, "status"))),
        detail("申请理由", s(current, "reason"))));
    if (current.get("decision") != null) {
      details.add(detail("最近决定", caseAction(s(current, "decision"))));
      details.add(detail("决定理由", s(current, "decision_reason")));
    }
    for (var decision : db.queryForList(
        "SELECT action,reason,created_at FROM case_decisions WHERE case_id=?"
            + " ORDER BY created_at DESC,id DESC", caseId)) {
      details.add(history("处理记录", caseAction(s(decision, "action")), decision));
    }
    return context(type, caseContext ? caseId : order, s(row, "title"), details,
        List.of(link("查看售后单", "/cases/" + caseId), link("查看订单", "/orders/" + order)));
  }

  private static Map<String, Object> history(String label, String action, Map<String, Object> row) {
    return detail(label + " · " + action, s(row, "reason") + "\n" + instant(row.get("created_at")));
  }

  private static String moderationState(String status) {
    return switch (status) {
      case "PENDING" -> "待审核";
      case "APPROVED" -> "审核通过";
      case "REJECTED" -> "审核驳回";
      default -> status;
    };
  }

  private static String caseState(String status) {
    return switch (status) {
      case "SELLER_REVIEW" -> "待卖家审核";
      case "BUYER_APPEAL" -> "等待买家申诉";
      case "SUPPORT_REVIEW" -> "客服处理中";
      case "RETURN_REQUIRED" -> "待买家寄回";
      case "RETURN_IN_TRANSIT" -> "退货运输中";
      case "RETURN_INSPECTION" -> "待卖家验收";
      case "REFUNDED" -> "已退款";
      case "CLOSED" -> "已关闭";
      default -> status;
    };
  }

  private static String caseAction(String action) {
    return switch (action) {
      case "OPEN_REFUND" -> "申请退款";
      case "OPEN_COMPLAINT" -> "提交投诉";
      case "REFUND" -> "同意退款";
      case "REJECT" -> "驳回申请";
      case "APPEAL" -> "提交申诉";
      case "WITHDRAW" -> "撤回申请";
      case "APPROVE_RETURN" -> "批准退货";
      case "ACCEPT_RETURN" -> "通过退货验收";
      case "DISPUTE" -> "提交验收异议";
      case "MORE_INFO" -> "要求补充材料";
      case "APPEAL_EXPIRED" -> "申诉超时";
      case "CLOSE" -> "关闭售后单";
      default -> action;
    };
  }

  private Map<String, Object> find(String sql, String id) {
    var rows = db.queryForList(sql, id);
    allowed(!rows.isEmpty());
    return rows.getFirst();
  }

  private static Map<String, Object> context(
      String type, String id, String title, List<Map<String, Object>> details,
      List<Map<String, Object>> links) {
    return map("type", type, "id", id, "title", title, "details", details, "links", links);
  }

  private static Map<String, Object> detail(String label, String value) {
    return map("label", label, "value", value);
  }

  private static Map<String, Object> link(String label, String path) {
    return map("label", label, "path", path);
  }
}
