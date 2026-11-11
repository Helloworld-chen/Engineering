package cn.edu.campus.trade;

import static cn.edu.campus.shared.BusinessException.*;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.aftersales.CaseRepositoryPort;
import cn.edu.campus.files.FilePort;
import cn.edu.campus.identity.IdentityPort;
import cn.edu.campus.notification.TimePort;
import cn.edu.campus.product.ProductPort;
import cn.edu.campus.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional
public class TradeService implements TradePort, TradeAfterSalesPort, TradeTaskPort {
  private final TradeMapper db;
  private final ProductPort products;
  private final IdentityPort identities;
  private final TimePort time;
  private final TransactionCoordinator locks;
  private final CaseRepositoryPort cases;
  private final FilePort files;
  private final JdbcTemplate shared;

  public TradeService(
      TradeMapper db,
      ProductPort products,
      IdentityPort identities,
      TimePort time,
      TransactionCoordinator locks,
      CaseRepositoryPort cases,
      FilePort files,
      JdbcTemplate shared) {
    this.db = db;
    this.products = products;
    this.identities = identities;
    this.time = time;
    this.locks = locks;
    this.cases = cases;
    this.files = files;
    this.shared = shared;
  }

  public Map<String, Object> place(Actor actor, String productId) {
    allowed(actor.is("USER"));
    var p = products.lock(productId);
    allowed(!actor.id().equals(s(p, "seller_id")));
    require(
        "APPROVED".equals(s(p, "audit_status"))
            && "AVAILABLE".equals(s(p, "sale_status"))
            && db.hold(productId) == null,
        "PRODUCT_UNAVAILABLE",
        "商品已被占用、下架或售出");
    long now = time.now();
    String id = id();
    var o =
        map(
            "id",
            id,
            "product_id",
            productId,
            "buyer_id",
            actor.id(),
            "seller_id",
            s(p, "seller_id"),
            "amount",
            n(p, "price"),
            "status",
            "PENDING_PAYMENT",
            "created_at",
            now,
            "pay_deadline",
            now + 120000);
    db.insertOrder(o);
    db.holdProduct(productId, id);
    products.updateSale(productId, "LOCKED");
    time.schedule(id, "PAYMENT", 0, now + 120000);
    time.emit(actor.id(), id, "ORDER_CREATED", "订单已创建，请在2分钟内付款");
    time.emit(s(p, "seller_id"), id, "ORDER_CREATED", "有买家下单，等待付款");
    return query(actor, id);
  }

  public Map<String, Object> cancel(Actor a, String id) {
    var o = locks.lock(id).order();
    allowed(a.id().equals(s(o, "buyer_id")));
    require("PENDING_PAYMENT".equals(s(o, "status")), "INVALID_STATE", "仅待付款订单可以取消");
    closeUnpaid(o, "CANCELLED");
    return query(a, id);
  }

  private void closeUnpaid(Map<String, Object> o, String reason) {
    o.put("status", "CLOSED");
    o.put("close_reason", reason);
    o.put("payment_generation", n(o, "payment_generation") + 1);
    db.release(s(o, "product_id"), s(o, "id"));
    products.updateSale(s(o, "product_id"), "AVAILABLE");
    db.saveOrder(o);
    notifyBoth(o, "ORDER_CLOSED", "待付款订单已关闭");
  }

  private Map<String, Map<String, Object>> accounts(String... ids) {
    var locked = new LinkedHashMap<String, Map<String, Object>>();
    for (String id : new TreeSet<>(List.of(ids))) {
      var row = db.lockAccount(id);
      require(row != null, "ACCOUNT_MISSING", "模拟账户未初始化");
      locked.put(id, row);
    }
    return locked;
  }

  public Map<String, Object> pay(Actor a, String id) {
    var context = locks.lock(id);
    var o = context.order();
    allowed(a.id().equals(s(o, "buyer_id")));
    require("PENDING_PAYMENT".equals(s(o, "status")), "INVALID_STATE", "订单不处于待付款状态");
    var accountRows = accounts(a.id());
    long now = time.now();
    require(now < n(o, "pay_deadline"), "PAYMENT_EXPIRED", "付款期限已到，未扣款");
    require(
        id.equals(db.hold(s(o, "product_id")))
            && "LOCKED".equals(s(context.product(), "sale_status")),
        "HOLD_MISMATCH",
        "商品占用不匹配");
    long amount = n(o, "amount");
    require(amount > 0, "AMOUNT_INVALID", "订单金额无效");
    require(n(accountRows.get(a.id()), "balance") >= amount, "INSUFFICIENT_FUNDS", "模拟余额不足，未扣款");
    db.addBalance(a.id(), -amount);
    db.escrowCreate(id, amount);
    money(o, "PAYMENT", a.id(), "PLATFORM", now);
    products.updateSale(s(o, "product_id"), "SOLD");
    o.put("status", "AWAITING_SHIPMENT");
    o.put("payment_generation", n(o, "payment_generation") + 1);
    db.saveOrder(o);
    time.emit(s(o, "buyer_id"), id, "PAID", "付款成功，货款由平台暂管");
    time.emit(s(o, "seller_id"), id, "PAID", "买家已付款，请及时发货");
    return query(a, id);
  }

  private void money(Map<String, Object> o, String kind, String from, String to, long now) {
    String operation = id();
    long amount = n(o, "amount");
    db.money(operation, s(o, "id"), kind, amount, now);
    db.entry(operation, from, -amount);
    db.entry(operation, to, amount);
  }

  public Map<String, Object> ship(
      Actor a,
      String id,
      String direction,
      String tracking,
      List<String> goods,
      List<String> packaging) {
    var context = locks.lock(id);
    var o = context.order();
    boolean returning = "RETURN".equals(direction);
    require(returning || "OUTBOUND".equals(direction), "INVALID_DIRECTION", "物流方向无效");
    allowed(a.id().equals(s(o, returning ? "buyer_id" : "seller_id")));
    if (db.shipment(id, direction) != null) return query(a, id);
    Map<String, Object> c = null;
    if (returning) {
      c =
          context.cases().stream()
              .filter(x -> "RETURN_REQUIRED".equals(s(x, "status")))
              .findFirst()
              .orElseThrow(() -> new BusinessException("RETURN_NOT_ALLOWED", "尚无有效退货决定", 409));
      require("HELD".equals(s(db.escrow(id), "status")), "INVALID_FUNDS", "货款未处于暂管状态");
    } else
      require(
          "AWAITING_SHIPMENT".equals(s(o, "status")) && !b(o, "shipping_blocked"),
          "SHIPMENT_BLOCKED",
          "订单未付款、已发货或售后处理中");
    require(
        tracking != null && !tracking.isBlank() && tracking.length() <= 100,
        "INVALID_TRACKING",
        "请填写模拟物流单号");
    require(
        goods != null
            && packaging != null
            && !goods.isEmpty()
            && !packaging.isEmpty()
            && goods.size() + packaging.size() <= 6,
        "INVALID_EVIDENCE",
        "需同时提供商品及包装照片，总数不超过6张");
    require(
        new HashSet<>(goods).size() == goods.size()
            && Collections.disjoint(goods, packaging)
            && new HashSet<>(packaging).size() == packaging.size(),
        "INVALID_EVIDENCE",
        "照片不能重复");
    files.link(a, goods, returning ? "RETURN_GOODS" : "GOODS", "ORDER", id);
    files.link(a, packaging, returning ? "RETURN_PACKAGING" : "PACKAGING", "ORDER", id);
    long now = time.now();
    var shipment =
        map(
            "id",
            id(),
            "order_id",
            id,
            "direction",
            direction,
            "tracking_number",
            tracking,
            "evidence",
            json(map("goods", goods, "packaging", packaging)),
            "shipped_at",
            now,
            "due_at",
            now + 10000);
    db.shipmentCreate(shipment);
    if (returning) {
      c.put("status", "RETURN_IN_TRANSIT");
      cases.save(c);
    } else o.put("status", "IN_TRANSIT");
    db.saveOrder(o);
    time.schedule(id, returning ? "RETURN_DELIVERY" : "DELIVERY", 0, now + 10000);
    notifyLogistics(o, returning, "SHIPPED", returning ? "买家已寄回商品，10秒后自动送达" : "卖家已发货，10秒后自动送达");
    return query(a, id);
  }

  public Map<String, Object> confirm(Actor a, String id) {
    var context = locks.lock(id);
    var o = context.order();
    allowed(a.id().equals(s(o, "buyer_id")));
    settle(o, context.cases());
    return query(a, id);
  }

  private boolean active(List<Map<String, Object>> list) {
    return list.stream().anyMatch(c -> !Set.of("CLOSED", "REFUNDED").contains(s(c, "status")));
  }

  private void settle(Map<String, Object> o, List<Map<String, Object>> list) {
    require(
        "DELIVERED".equals(s(o, "status"))
            && !b(o, "settlement_blocked")
            && !b(o, "receipt_paused")
            && !active(list),
        "SETTLEMENT_BLOCKED",
        "需商品已送达且无待处理售后，才能确认收货");
    accounts(s(o, "seller_id"));
    var e = db.lockEscrow(s(o, "id"));
    require(
        e != null && "HELD".equals(s(e, "status")) && n(e, "amount") == n(o, "amount"),
        "INVALID_ESCROW",
        "暂管货款状态不符");
    require(db.escrowState(s(o, "id"), "SETTLED") == 1, "INVALID_ESCROW", "货款已处理");
    db.addBalance(s(o, "seller_id"), n(o, "amount"));
    money(o, "SETTLEMENT", "PLATFORM", s(o, "seller_id"), time.now());
    o.put("status", "COMPLETED");
    o.put("receipt_generation", n(o, "receipt_generation") + 1);
    db.saveOrder(o);
    notifyBoth(o, "SETTLED", "订单已完成，货款已结算给卖家");
  }

  /** M07 supplies a persisted, verified decision within the same transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void applyCaseDecision(String orderId, String decisionId) {
    var context = locks.lock(orderId);
    var o = context.order();
    var decision = cases.decision(decisionId);
    require(decision != null, "INVALID_DECISION", "售后决定不存在");
    var c =
        context.cases().stream()
            .filter(x -> s(x, "id").equals(s(decision, "case_id")))
            .findFirst()
            .orElseThrow(() -> new BusinessException("INVALID_DECISION", "售后决定不属于该订单", 409));
    if (shared.queryForObject(
            "SELECT COUNT(*) FROM applied_decisions WHERE decision_id=?", Integer.class, decisionId)
        > 0) return;
    String action = s(decision, "action");
    require(
        !Set.of("CLOSED", "COMPLETED").contains(s(o, "status")), "ORDER_FINISHED", "订单已结束，无法继续售后");
    if (Set.of("OPEN_REFUND", "OPEN_COMPLAINT", "APPROVE_RETURN").contains(action)) {
      o.put("shipping_blocked", true);
      o.put("settlement_blocked", true);
      if ("DELIVERED".equals(s(o, "status")) && !b(o, "receipt_paused")) {
        o.put("receipt_remaining", Math.max(0, n(o, "receipt_deadline") - time.now()));
        o.put("receipt_paused", true);
        o.put("receipt_generation", n(o, "receipt_generation") + 1);
      }
    } else if (Set.of("REJECT", "WITHDRAW", "APPEAL_EXPIRED").contains(action)) {
      require("CLOSED".equals(s(c, "status")), "INVALID_DECISION", "售后仍在处理或申诉期间，不能解除交易限制");
      boolean other =
          context.cases().stream()
              .anyMatch(
                  x ->
                      !s(x, "id").equals(s(c, "id"))
                          && !Set.of("CLOSED", "REFUNDED").contains(s(x, "status")));
      if (!other) {
        o.put("shipping_blocked", false);
        o.put("settlement_blocked", false);
        if (b(o, "receipt_paused")) {
          long deadline = time.now() + n(o, "receipt_remaining");
          o.put("receipt_deadline", deadline);
          o.put("receipt_paused", false);
          o.put("receipt_generation", n(o, "receipt_generation") + 1);
          time.schedule(orderId, "RECEIPT", n(o, "receipt_generation"), deadline);
        }
      }
    } else if ("REFUND".equals(action)) {
      require(Set.of("REFUNDED").contains(s(c, "status")), "INVALID_DECISION", "退款决定尚未生效");
      accounts(s(o, "buyer_id"));
      var e = db.lockEscrow(orderId);
      require(
          e != null && "HELD".equals(s(e, "status")) && n(e, "amount") == n(o, "amount"),
          "INVALID_ESCROW",
          "货款已处理");
      require(db.escrowState(orderId, "REFUNDED") == 1, "INVALID_ESCROW", "货款已处理");
      db.addBalance(s(o, "buyer_id"), n(o, "amount"));
      money(o, "REFUND", "PLATFORM", s(o, "buyer_id"), time.now());
      o.put("status", "CLOSED");
      o.put("close_reason", "REFUNDED");
      o.put("receipt_generation", n(o, "receipt_generation") + 1);
      notifyBoth(o, "REFUNDED", "原订单货款已全额退回，不含退货运费");
    } else throw new BusinessException("INVALID_DECISION", "不支持的交易决定", 409);
    db.saveOrder(o);
    shared.update("INSERT INTO applied_decisions VALUES(?,?)", decisionId, orderId);
  }

  public void deadline(String id, String kind, long generation, long dueAt) {
    var context = locks.lock(id);
    var o = context.order();
    if (time.now() < dueAt) return;
    switch (kind) {
      case "PAYMENT" -> {
        if (generation == n(o, "payment_generation")
            && "PENDING_PAYMENT".equals(s(o, "status"))
            && time.now() >= n(o, "pay_deadline")) closeUnpaid(o, "PAYMENT_EXPIRED");
      }
      case "RECEIPT" -> {
        if (generation == n(o, "receipt_generation")
            && "DELIVERED".equals(s(o, "status"))
            && !b(o, "receipt_paused")
            && !b(o, "settlement_blocked")
            && !active(context.cases())
            && time.now() >= n(o, "receipt_deadline")) settle(o, context.cases());
      }
      case "DELIVERY", "RETURN_DELIVERY" -> {
        boolean returning = kind.equals("RETURN_DELIVERY");
        var shipment = db.shipment(id, returning ? "RETURN" : "OUTBOUND");
        if (shipment == null
            || !"IN_TRANSIT".equals(s(shipment, "status"))
            || generation != n(shipment, "generation")
            || time.now() < n(shipment, "due_at")) return;
        if (!returning && !"IN_TRANSIT".equals(s(o, "status"))) return;
        db.delivered(s(shipment, "id"));
        long delivered = n(shipment, "due_at");
        if (returning) {
          var c =
              context.cases().stream()
                  .filter(x -> "RETURN_IN_TRANSIT".equals(s(x, "status")))
                  .findFirst()
                  .orElseThrow();
          c.put("status", "RETURN_INSPECTION");
          c.put("deadline", delivered + 86400000L);
          c.put("generation", n(c, "generation") + 1);
          cases.save(c);
          time.schedule(s(c, "id"), "RETURN_INSPECTION", n(c, "generation"), delivered + 86400000L);
        } else {
          o.put("status", "DELIVERED");
          o.put("receipt_deadline", delivered + 172800000L);
          o.put("receipt_generation", n(o, "receipt_generation") + 1);
          time.schedule(id, "RECEIPT", n(o, "receipt_generation"), delivered + 172800000L);
        }
        db.saveOrder(o);
        notifyLogistics(o, returning, "DELIVERED", returning ? "退货已送达，卖家有24小时验收" : "商品已送达，48小时内可确认收货或申请售后");
      }
      default -> throw new BusinessException("INVALID_TASK", "未知交易任务", 409);
    }
  }

  public Map<String, Object> query(Actor a, String id) {
    var o = db.order(id);
    if (o == null) throw new BusinessException("NOT_FOUND", "订单不存在", 404);
    var cs = cases.forOrder(id);
    allowed(
        a.id().equals(s(o, "buyer_id"))
            || a.id().equals(s(o, "seller_id"))
            || a.is("SUPPORT") && !cs.isEmpty());
    var v = view(o);
    v.put("buyerName", identities.find(s(o, "buyer_id")).displayName());
    v.put("sellerName", identities.find(s(o, "seller_id")).displayName());
    v.put("product", products.get(s(o, "product_id"), a));
    v.put("serverTime", instant(time.now()));
    var escrow = db.escrow(id);
    v.put("fundsStatus", escrow == null ? "UNPAID" : s(escrow, "status"));
    v.put(
        "shipments",
        db.shipments(id).stream()
            .map(
                x -> {
                  var m = view(x);
                  m.put("evidence", object(s(x, "evidence")));
                  return m;
                })
            .toList());
    v.put("cases", cs.stream().map(Rows::view).toList());
    v.put("allowedActions", actions(a, o, cs));
    return v;
  }

  private List<String> actions(Actor a, Map<String, Object> o, List<Map<String, Object>> cs) {
    var actions = new ArrayList<String>();
    boolean buyer = a.id().equals(s(o, "buyer_id")), seller = a.id().equals(s(o, "seller_id"));
    String state = s(o, "status");
    if (buyer && state.equals("PENDING_PAYMENT")) {
      actions.add("CANCEL");
      if (time.now() < n(o, "pay_deadline")) actions.add("PAY");
    }
    if (seller && state.equals("AWAITING_SHIPMENT") && !b(o, "shipping_blocked"))
      actions.add("SHIP");
    if (buyer && state.equals("AWAITING_SHIPMENT") && !active(cs)) actions.add("REQUEST_REFUND");
    if (buyer && state.equals("DELIVERED") && !active(cs) && !b(o, "settlement_blocked")) {
      actions.add("CONFIRM");
      actions.add("COMPLAIN");
    }
    if (buyer && cs.stream().anyMatch(c -> s(c, "status").equals("RETURN_REQUIRED")))
      actions.add("RETURN_SHIP");
    return actions;
  }

  public List<Map<String, Object>> list(Actor a, String side) {
    return db.orders(a.id()).stream()
        .filter(o -> a.id().equals(s(o, side.equals("selling") ? "seller_id" : "buyer_id")))
        .map(o -> query(a, s(o, "id")))
        .toList();
  }

  private void notifyLogistics(Map<String, Object> order, boolean returning, String kind, String message) {
    time.emit(s(order, returning ? "seller_id" : "buyer_id"), s(order, "id"), kind, message);
    time.changed(s(order, returning ? "buyer_id" : "seller_id"), s(order, "id"));
  }

  private void notifyBoth(Map<String, Object> o, String kind, String message) {
    time.emit(s(o, "buyer_id"), s(o, "id"), kind, message);
    time.emit(s(o, "seller_id"), s(o, "id"), kind, message);
  }
}
