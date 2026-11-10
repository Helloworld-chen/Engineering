package cn.edu.campus.aftersales;

import static cn.edu.campus.shared.BusinessException.*;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.notification.TimePort;
import cn.edu.campus.shared.*;
import cn.edu.campus.trade.TradeAfterSalesPort;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@ConditionalOnProperty(name = "market.providers.aftersales", havingValue = "demo")
public class DemoAftersalesAdapter implements AftersalesPort {
  private final JdbcTemplate db;
  private final TransactionCoordinator locks;
  private final CaseRepositoryPort repository;
  private final TradeAfterSalesPort trade;
  private final TimePort time;

  public DemoAftersalesAdapter(
      JdbcTemplate db,
      TransactionCoordinator locks,
      CaseRepositoryPort repository,
      TradeAfterSalesPort trade,
      TimePort time) {
    this.db = db;
    this.locks = locks;
    this.repository = repository;
    this.trade = trade;
    this.time = time;
  }

  public Map<String, Object> open(Actor a, String orderId, String kind, String reason) {
    var context = locks.lock(orderId);
    var o = context.order();
    allowed(a.id().equals(s(o, "buyer_id")));
    require(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "INVALID_REASON",
        "请填写1至2000字的售后理由");
    require(
        context.cases().stream()
            .noneMatch(c -> !Set.of("CLOSED", "REFUNDED").contains(s(c, "status"))),
        "ACTIVE_CASE",
        "已有进行中的售后");
    boolean refund = "REFUND".equals(kind);
    require(refund || "COMPLAINT".equals(kind), "INVALID_CASE", "售后类型不正确");
    require(
        (refund ? "AWAITING_SHIPMENT" : "DELIVERED").equals(s(o, "status")),
        "CASE_NOT_ALLOWED",
        "当前订单状态不允许此类售后");
    String id = id();
    long now = time.now();
    Long deadline = refund ? now + 86400000L : null;
    db.update(
        "INSERT INTO cases(id,order_id,buyer_id,kind,reason,status,deadline,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        orderId,
        a.id(),
        kind,
        reason,
        refund ? "SELLER_REVIEW" : "SUPPORT_REVIEW",
        deadline,
        now);
    if (refund) time.schedule(id, "REFUND_REVIEW", 0, deadline);
    String decision = decision(id, a.id(), refund ? "OPEN_REFUND" : "OPEN_COMPLAINT", reason);
    trade.applyCaseDecision(orderId, decision);
    time.emit(s(o, "seller_id"), orderId, "CASE_OPENED", "买家已申请售后，请查看处理进度");
    return view(get(id));
  }

  private Map<String, Object> get(String id) {
    var rows = db.queryForList("SELECT * FROM cases WHERE id=?", id);
    if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "售后单不存在", 404);
    return rows.getFirst();
  }

  private String decision(String id, String actor, String action, String reason) {
    String decision = id();
    db.update(
        "INSERT INTO case_decisions VALUES(?,?,?,?,?,?)",
        decision,
        id,
        actor,
        action,
        reason,
        time.now());
    return decision;
  }

  public Map<String, Object> act(Actor a, String id, String action, String reason) {
    var ref = get(id);
    var context = locks.lock(s(ref, "order_id"));
    var o = context.order();
    var c = context.cases().stream().filter(x -> id.equals(s(x, "id"))).findFirst().orElseThrow();
    String state = s(c, "status");
    boolean buyer = a.id().equals(s(o, "buyer_id")), seller = a.id().equals(s(o, "seller_id"));
    allowed(buyer || seller || a.is("SUPPORT"));
    require(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "INVALID_REASON",
        "请填写处理理由");
    String effect = null;
    if (seller && state.equals("SELLER_REVIEW")) {
      require(time.now() < n(c, "deadline"), "CASE_EXPIRED", "审核已到期，请等待客服处理");
      if (action.equals("REFUND")) {
        c.put("status", "REFUNDED");
        effect = "REFUND";
      } else if (action.equals("REJECT")) {
        c.put("status", "BUYER_APPEAL");
        c.put("deadline", time.now() + 86400000L);
        c.put("generation", n(c, "generation") + 1);
        time.schedule(id, "APPEAL", n(c, "generation"), n(c, "deadline"));
      } else throw invalid();
    } else if (buyer && Set.of("SELLER_REVIEW", "BUYER_APPEAL").contains(state)) {
      if (action.equals("WITHDRAW")) {
        c.put("status", "CLOSED");
        effect = "WITHDRAW";
      } else if (action.equals("APPEAL") && state.equals("BUYER_APPEAL")) {
        require(time.now() < n(c, "deadline"), "CASE_EXPIRED", "申诉期限已到");
        c.put("status", "SUPPORT_REVIEW");
      } else throw invalid();
    } else if (seller && state.equals("RETURN_INSPECTION")) {
      require(time.now() < n(c, "deadline"), "CASE_EXPIRED", "退货验收已到期，请等待客服处理");
      if (action.equals("ACCEPT_RETURN")) {
        c.put("status", "REFUNDED");
        effect = "REFUND";
      } else if (action.equals("DISPUTE")) c.put("status", "SUPPORT_REVIEW");
      else throw invalid();
    } else if (a.is("SUPPORT") && state.equals("SUPPORT_REVIEW")) {
      boolean hasReturn =
          db.queryForObject(
                  "SELECT COUNT(*) FROM shipments WHERE order_id=? AND direction='RETURN'",
                  Integer.class,
                  o.get("id"))
              > 0;
      boolean deliveredReturn =
          db.queryForObject(
                  "SELECT COUNT(*) FROM shipments WHERE order_id=? AND direction='RETURN' AND"
                      + " status='DELIVERED'",
                  Integer.class,
                  o.get("id"))
              > 0;
      switch (action) {
        case "REJECT" -> {
          require(!hasReturn, "RETURN_DISPUTE", "退货争议可退款或要求补充材料");
          c.put("status", "CLOSED");
          effect = "REJECT";
        }
        case "APPROVE_RETURN" -> {
          require("COMPLAINT".equals(s(c, "kind")) && !hasReturn, "INVALID_CASE", "当前售后无法再次批准退货");
          c.put("status", "RETURN_REQUIRED");
          effect = "APPROVE_RETURN";
        }
        case "REFUND" -> {
          require(
              "REFUND".equals(s(c, "kind")) || deliveredReturn,
              "RETURN_REQUIRED",
              "送达后投诉需先完成退货及验收流程");
          c.put("status", "REFUNDED");
          effect = "REFUND";
        }
        case "MORE_INFO" -> {
          /* State stays with support; funds remain held. */
        }
        default -> throw invalid();
      }
    } else throw invalid();
    c.put("decision", action);
    c.put("decision_reason", reason);
    if (!"BUYER_APPEAL".equals(s(c, "status"))) {
      c.put("deadline", null);
      c.put("generation", n(c, "generation") + 1);
    }
    repository.save(c);
    String d = decision(id, a.id(), effect == null ? action : effect, reason);
    if (effect != null) trade.applyCaseDecision(s(o, "id"), d);
    time.emit(s(o, "buyer_id"), s(o, "id"), "CASE_CHANGED", "售后处理进度已更新");
    time.emit(s(o, "seller_id"), s(o, "id"), "CASE_CHANGED", "售后处理进度已更新");
    return view(get(id));
  }

  private BusinessException invalid() {
    return new BusinessException("CASE_ACTION_NOT_ALLOWED", "当前身份或售后状态不允许此操作", 409);
  }

  public List<Map<String, Object>> list(Actor a) {
    String where = a.is("SUPPORT") ? "" : " WHERE o.buyer_id=? OR o.seller_id=?";
    return db
        .queryForList(
            "SELECT c.* FROM cases c JOIN orders o ON o.id=c.order_id"
                + where
                + " ORDER BY c.created_at DESC",
            a.is("SUPPORT") ? new Object[] {} : new Object[] {a.id(), a.id()})
        .stream()
        .map(Rows::view)
        .toList();
  }

  public Map<String, Object> message(Actor a, String caseId, String content) {
    var ref = get(caseId);
    var context = locks.lock(s(ref, "order_id"));
    var o = context.order();
    boolean buyer = a.id().equals(s(o, "buyer_id")), seller = a.id().equals(s(o, "seller_id"));
    allowed(buyer || seller || a.is("SUPPORT"));
    require(
        content != null && !content.isBlank() && content.length() <= 500,
        "INVALID_MESSAGE",
        "留言长度应为1至500个字符");
    String messageId = id();
    String role = a.is("SUPPORT") ? "SUPPORT" : buyer ? "BUYER" : "SELLER";
    db.update(
        "INSERT INTO case_messages(id,case_id,sender_id,role,content,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        messageId,
        caseId,
        a.id(),
        role,
        content.trim(),
        time.now());
    if (!buyer) time.emit(s(o, "buyer_id"), caseId, "CASE_MESSAGE", "售后有一条新留言");
    if (!seller) time.emit(s(o, "seller_id"), caseId, "CASE_MESSAGE", "售后有一条新留言");
    return view(db.queryForMap("SELECT * FROM case_messages WHERE id=?", messageId));
  }

  public List<Map<String, Object>> messages(Actor a, String caseId) {
    var ref = get(caseId);
    var rows = db.queryForList("SELECT * FROM orders WHERE id=?", s(ref, "order_id"));
    if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "订单不存在", 404);
    var o = rows.getFirst();
    allowed(
        a.is("SUPPORT")
            || a.id().equals(s(o, "buyer_id"))
            || a.id().equals(s(o, "seller_id")));
    return db.queryForList(
            "SELECT * FROM case_messages WHERE case_id=? ORDER BY created_at,id", caseId)
        .stream()
        .map(Rows::view)
        .toList();
  }

  public void deadline(String id, String kind, long generation, long dueAt) {
    var ref = get(id);
    var context = locks.lock(s(ref, "order_id"));
    var c = context.cases().stream().filter(x -> id.equals(s(x, "id"))).findFirst().orElseThrow();
    if (generation != n(c, "generation")
        || c.get("deadline") == null
        || time.now() < n(c, "deadline")) return;
    String state = s(c, "status"), effect = null;
    if (kind.equals("REFUND_REVIEW") && state.equals("SELLER_REVIEW")
        || kind.equals("RETURN_INSPECTION") && state.equals("RETURN_INSPECTION"))
      c.put("status", "SUPPORT_REVIEW");
    else if (kind.equals("APPEAL") && state.equals("BUYER_APPEAL")) {
      c.put("status", "CLOSED");
      effect = "APPEAL_EXPIRED";
    } else return;
    c.put("deadline", null);
    c.put("generation", n(c, "generation") + 1);
    repository.save(c);
    if (effect != null)
      trade.applyCaseDecision(s(c, "order_id"), decision(id, "SYSTEM", effect, "申诉期限届满"));
    time.emit(s(context.order(), "buyer_id"), s(c, "order_id"), "CASE_DEADLINE", "售后期限已到，处理进度已更新");
  }
}
