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

/**
 * M07 售后审核与客服裁决的正式实现。
 *
 * <p>职责：售后单的受理、卖家退款审核、买家申诉、客服裁决、退货验收与升级处理。
 * 本模块只作出业务决定，账户与资金的实际变更由 M02 通过 {@link TradeAfterSalesPort}
 * 在同一事务内执行。锁顺序固定为 商品 → 订单 → 售后单。
 *
 * <p>状态机：SELLER_REVIEW（待卖家审核）→ BUYER_APPEAL（待买家申诉）
 * → SUPPORT_REVIEW（客服处理中）→ RETURN_REQUIRED（待寄回）→ RETURN_INSPECTION（待验收）
 * → REFUNDED / CLOSED。
 */
@Service
@Transactional
@ConditionalOnProperty(name = "market.providers.aftersales", havingValue = "real")
public class RealAftersalesAdapter implements AftersalesPort {
  /** 卖家退款审核期限：CR-02 变更后为 48 小时（原 24 小时）。 */
  private static final long REVIEW_WINDOW = 172_800_000L; // 48 小时

  private static final Set<String> TERMINAL = Set.of("CLOSED", "REFUNDED");

  private final JdbcTemplate db;
  private final TransactionCoordinator locks;
  private final CaseRepositoryPort repository;
  private final TradeAfterSalesPort trade;
  private final TimePort time;

  public RealAftersalesAdapter(
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

  // ---------------------------------------------------------------- 受理

  @Override
  public Map<String, Object> open(Actor actor, String orderId, String kind, String reason) {
    var context = locks.lock(orderId);
    var order = context.order();
    allowed(actor.id().equals(s(order, "buyer_id")));
    require(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "INVALID_REASON",
        "请填写1至2000字的售后理由");
    require(
        context.cases().stream().noneMatch(c -> !TERMINAL.contains(s(c, "status"))),
        "ACTIVE_CASE",
        "已有进行中的售后");

    boolean refund = "REFUND".equals(kind);
    require(refund || "COMPLAINT".equals(kind), "INVALID_CASE", "售后类型不正确");
    require(
        (refund ? "AWAITING_SHIPMENT" : "DELIVERED").equals(s(order, "status")),
        "CASE_NOT_ALLOWED",
        "当前订单状态不允许此类售后");

    String caseId = id();
    long now = time.now();
    Long due = refund ? now + REVIEW_WINDOW : null;
    db.update(
        "INSERT INTO cases(id,order_id,buyer_id,kind,reason,status,deadline,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        caseId,
        orderId,
        actor.id(),
        kind,
        reason,
        refund ? "SELLER_REVIEW" : "SUPPORT_REVIEW",
        due,
        now);
    if (refund) time.schedule(caseId, "REFUND_REVIEW", 0, due);
    String decision =
        recordDecision(caseId, actor.id(), refund ? "OPEN_REFUND" : "OPEN_COMPLAINT", reason);
    trade.applyCaseDecision(orderId, decision);
    time.emit(s(order, "seller_id"), orderId, "CASE_OPENED", "买家已申请售后，请查看处理进度");
    return view(requireCase(caseId));
  }

  // ---------------------------------------------------------------- 处理

  @Override
  public Map<String, Object> act(Actor actor, String caseId, String action, String reason) {
    var reference = requireCase(caseId);
    var context = locks.lock(s(reference, "order_id"));
    var order = context.order();
    var row =
        context.cases().stream()
            .filter(c -> caseId.equals(s(c, "id")))
            .findFirst()
            .orElseThrow(() -> new BusinessException("NOT_FOUND", "售后单不存在", 404));

    String state = s(row, "status");
    boolean buyer = actor.id().equals(s(order, "buyer_id"));
    boolean seller = actor.id().equals(s(order, "seller_id"));
    allowed(buyer || seller || actor.is("SUPPORT"));
    require(
        reason != null && !reason.isBlank() && reason.length() <= 2000,
        "INVALID_REASON",
        "请填写处理理由");

    String effect = null;
    if (seller && "SELLER_REVIEW".equals(state)) {
      effect = sellerReview(row, action);
    } else if (buyer && Set.of("SELLER_REVIEW", "BUYER_APPEAL").contains(state)) {
      effect = buyerAction(row, action, state);
    } else if (seller && "RETURN_INSPECTION".equals(state)) {
      effect = returnInspection(row, action);
    } else if (actor.is("SUPPORT") && "SUPPORT_REVIEW".equals(state)) {
      effect = supportDecision(row, order, action);
    } else {
      throw invalidAction();
    }

    row.put("decision", action);
    row.put("decision_reason", reason);
    if (!"BUYER_APPEAL".equals(s(row, "status"))) {
      row.put("deadline", null);
      row.put("generation", n(row, "generation") + 1);
    }
    repository.save(row);
    String decisionId = recordDecision(caseId, actor.id(), effect == null ? action : effect, reason);
    if (effect != null) trade.applyCaseDecision(s(order, "id"), decisionId);
    time.emit(s(order, "buyer_id"), s(order, "id"), "CASE_CHANGED", "售后处理进度已更新");
    time.emit(s(order, "seller_id"), s(order, "id"), "CASE_CHANGED", "售后处理进度已更新");
    return view(requireCase(caseId));
  }

  /** 卖家在审核期内同意或拒绝退款。 */
  private String sellerReview(Map<String, Object> row, String action) {
    require(time.now() < n(row, "deadline"), "CASE_EXPIRED", "审核已到期，请等待客服处理");
    if ("REFUND".equals(action)) {
      row.put("status", "REFUNDED");
      return "REFUND";
    }
    if ("REJECT".equals(action)) {
      row.put("status", "BUYER_APPEAL");
      row.put("deadline", time.now() + REVIEW_WINDOW);
      row.put("generation", n(row, "generation") + 1);
      time.schedule(
          s(row, "id"), "APPEAL", n(row, "generation"), n(row, "deadline"));
      return null;
    }
    throw invalidAction();
  }

  /** 买家撤回退款申请，或在申诉期内提出申诉。 */
  private String buyerAction(Map<String, Object> row, String action, String state) {
    if ("WITHDRAW".equals(action)) {
      row.put("status", "CLOSED");
      return "WITHDRAW";
    }
    if ("APPEAL".equals(action) && "BUYER_APPEAL".equals(state)) {
      require(time.now() < n(row, "deadline"), "CASE_EXPIRED", "申诉期限已到");
      row.put("status", "SUPPORT_REVIEW");
      return null;
    }
    throw invalidAction();
  }

  /** 卖家在验收期内确认收回商品或提出异议。 */
  private String returnInspection(Map<String, Object> row, String action) {
    require(time.now() < n(row, "deadline"), "CASE_EXPIRED", "退货验收已到期，请等待客服处理");
    if ("ACCEPT_RETURN".equals(action)) {
      row.put("status", "REFUNDED");
      return "REFUND";
    }
    if ("DISPUTE".equals(action)) {
      row.put("status", "SUPPORT_REVIEW");
      return null;
    }
    throw invalidAction();
  }

  /** 客服裁决：驳回、批准退货、退款或要求补充材料。 */
  private String supportDecision(Map<String, Object> row, Map<String, Object> order, String action) {
    boolean hasReturn =
        db.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE order_id=? AND direction='RETURN'",
                Integer.class,
                order.get("id"))
            > 0;
    boolean deliveredReturn =
        db.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE order_id=? AND direction='RETURN'"
                    + " AND status='DELIVERED'",
                Integer.class,
                order.get("id"))
            > 0;
    switch (action) {
      case "REJECT" -> {
        require(!hasReturn, "RETURN_DISPUTE", "退货争议可退款或要求补充材料");
        row.put("status", "CLOSED");
        return "REJECT";
      }
      case "APPROVE_RETURN" -> {
        require(
            "COMPLAINT".equals(s(row, "kind")) && !hasReturn,
            "INVALID_CASE",
            "当前售后无法再次批准退货");
        row.put("status", "RETURN_REQUIRED");
        return "APPROVE_RETURN";
      }
      case "REFUND" -> {
        require(
            "REFUND".equals(s(row, "kind")) || deliveredReturn,
            "RETURN_REQUIRED",
            "送达后投诉需先完成退货及验收流程");
        row.put("status", "REFUNDED");
        return "REFUND";
      }
      case "MORE_INFO" -> {
        // 状态留在客服处理，暂管款继续冻结，等待补充材料。
        return null;
      }
      default -> throw invalidAction();
    }
  }

  // ---------------------------------------------------------------- 查询

  @Override
  public List<Map<String, Object>> list(Actor actor) {
    String where = actor.is("SUPPORT") ? "" : " WHERE o.buyer_id=? OR o.seller_id=?";
    Object[] params = actor.is("SUPPORT") ? new Object[] {} : new Object[] {actor.id(), actor.id()};
    return db
        .queryForList(
            "SELECT c.* FROM cases c JOIN orders o ON o.id=c.order_id" + where
                + " ORDER BY c.created_at DESC",
            params)
        .stream()
        .map(Rows::view)
        .toList();
  }

  // ---------------------------------------------------------------- 期限

  @Override
  public void deadline(String caseId, String kind, long generation, long dueAt) {
    var reference = requireCase(caseId);
    var context = locks.lock(s(reference, "order_id"));
    var row =
        context.cases().stream()
            .filter(c -> caseId.equals(s(c, "id")))
            .findFirst()
            .orElseThrow();
    if (generation != n(row, "generation")
        || row.get("deadline") == null
        || time.now() < n(row, "deadline")) return;

    String state = s(row, "status");
    String effect = null;
    boolean reviewTimeout = "REFUND_REVIEW".equals(kind) && "SELLER_REVIEW".equals(state);
    boolean inspectionTimeout =
        "RETURN_INSPECTION".equals(kind) && "RETURN_INSPECTION".equals(state);
    if (reviewTimeout || inspectionTimeout) {
      row.put("status", "SUPPORT_REVIEW");
    } else if ("APPEAL".equals(kind) && "BUYER_APPEAL".equals(state)) {
      row.put("status", "CLOSED");
      effect = "APPEAL_EXPIRED";
    } else {
      return;
    }
    row.put("deadline", null);
    row.put("generation", n(row, "generation") + 1);
    repository.save(row);
    if (effect != null) {
      String decisionId = recordDecision(caseId, "SYSTEM", effect, "申诉期限届满");
      trade.applyCaseDecision(s(row, "order_id"), decisionId);
    }
    time.emit(
        s(context.order(), "buyer_id"),
        s(row, "order_id"),
        "CASE_DEADLINE",
        "售后期限已到，处理进度已更新");
  }

  // ---------------------------------------------------------------- 内部

  private Map<String, Object> requireCase(String caseId) {
    var rows = db.queryForList("SELECT * FROM cases WHERE id=?", caseId);
    if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "售后单不存在", 404);
    return rows.getFirst();
  }

  private String recordDecision(String caseId, String actorId, String action, String reason) {
    String decisionId = id();
    db.update(
        "INSERT INTO case_decisions VALUES(?,?,?,?,?,?)",
        decisionId,
        caseId,
        actorId,
        action,
        reason,
        time.now());
    return decisionId;
  }

  private BusinessException invalidAction() {
    return new BusinessException("CASE_ACTION_NOT_ALLOWED", "当前身份或售后状态不允许此操作", 409);
  }
}
