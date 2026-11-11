package cn.edu.campus.review;

import static cn.edu.campus.shared.BusinessException.allowed;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.aftersales.CaseRepositoryPort;
import cn.edu.campus.notification.TimePort;
import cn.edu.campus.shared.*;
import cn.edu.campus.trade.TradeMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class OrderReviewService {
  public static final String DEADLINE_KIND = "REVIEW_PUBLICATION";
  private static final long WINDOW = 604_800_000L;
  private static final String PUBLICATION_EVENT = "REVIEWS_PUBLISHED";
  private static final String NO_SETTLEMENT = "订单完成并结算后才可评价";
  private static final String EXPIRED = "7天评价期限已结束";
  private static final String ALREADY_SUBMITTED = "已提交本次评价";
  private final JdbcTemplate db;
  private final TradeMapper trade;
  private final CaseRepositoryPort cases;
  private final TimePort time;
  private final PublicReviewQueries publicReviews;

  public OrderReviewService(JdbcTemplate db, TradeMapper trade, CaseRepositoryPort cases,
      TimePort time, PublicReviewQueries publicReviews) {
    this.db = db;
    this.trade = trade;
    this.cases = cases;
    this.time = time;
    this.publicReviews = publicReviews;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> get(Actor actor, String id) {
    allowed(actor.is("USER") || actor.is("SUPPORT"));
    var order = requireOrder(trade.order(id));
    boolean participant = actor.is("USER") && participates(actor.id(), order);
    allowed(participant || actor.is("SUPPORT") && !cases.forOrder(id).isEmpty());
    return snapshot(actor, order, participant, time.now());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> submit(Actor actor, String id, int stars, String comment) {
    allowed(actor.is("USER"));
    // Settlement and the deadline worker also serialize on this order row.
    var order = requireOrder(trade.lockOrder(id));
    allowed(participates(actor.id(), order));
    long now = time.now();
    Long deadline = deadline(order, now);
    if (deadline == null) throw new BusinessException("REVIEW_NOT_ELIGIBLE", NO_SETTLEMENT, 409);
    if (now >= deadline) throw new BusinessException("REVIEW_EXPIRED", EXPIRED, 409);
    if (ownReview(actor, id) != null) {
      throw new BusinessException("REVIEW_ALREADY_SUBMITTED", ALREADY_SUBMITTED, 409);
    }

    boolean fromBuyer = actor.id().equals(s(order, "buyer_id"));
    db.update(
        "INSERT INTO order_reviews(id,order_id,reviewer_id,subject_id,subject_role,stars,comment,"
            + "submitted_at) VALUES(?,?,?,?,?,?,?,?)",
        id(), id, actor.id(), s(order, fromBuyer ? "seller_id" : "buyer_id"),
        fromBuyer ? "SELLER" : "BUYER", stars, comment, now);
    if (db.queryForObject("SELECT COUNT(*) FROM order_reviews WHERE order_id=?", Integer.class, id) == 2) {
      publish(order, now);
      db.update("UPDATE deadlines SET status='DONE' WHERE aggregate_id=? AND kind=? AND generation=0",
          id, DEADLINE_KIND);
    } else {
      time.schedule(id, DEADLINE_KIND, 0, deadline);
      time.changed(actor.id(), id);
    }
    return snapshot(actor, order, true, now);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void publishDue(String id, long generation, long dueAt) {
    var order = trade.lockOrder(id);
    if (order == null || generation != 0) return;
    long now = time.now();
    Long deadline = deadline(order, now);
    if (deadline != null && deadline == dueAt && now >= dueAt) publish(order, dueAt);
  }

  private void publish(Map<String, Object> order, long publishedAt) {
    String id = s(order, "id");
    if (db.update(
        "UPDATE order_reviews SET published_at=? WHERE order_id=? AND published_at IS NULL",
        publishedAt, id) > 0) {
      String message = "订单评价已公开";
      time.emit(s(order, "buyer_id"), id, PUBLICATION_EVENT, message);
      time.emit(s(order, "seller_id"), id, PUBLICATION_EVENT, message);
    }
  }

  private Map<String, Object> snapshot(
      Actor actor, Map<String, Object> order, boolean participant, long now) {
    String id = s(order, "id");
    Long deadline = deadline(order, now);
    var own = participant ? ownReview(actor, id) : null;
    String reason;
    if (!participant) reason = "仅订单买卖双方可提交评价";
    else if (own != null) reason = ALREADY_SUBMITTED;
    else if (deadline == null) reason = NO_SETTLEMENT;
    else if (now >= deadline) reason = EXPIRED;
    else reason = "";
    return map("eligible", reason.isEmpty(), "reason", reason, "deadline", instant(deadline),
        "serverTime", instant(now), "ownReview", own, "items", publicReviews.forOrder(id));
  }

  private Map<String, Object> ownReview(Actor actor, String id) {
    var rows = db.queryForList(
        "SELECT id,stars,comment,submitted_at,published_at FROM order_reviews"
            + " WHERE order_id=? AND reviewer_id=?", id, actor.id());
    return rows.isEmpty() ? null : view(rows.getFirst());
  }

  private Long deadline(Map<String, Object> order, long now) {
    if (!"COMPLETED".equals(s(order, "status"))) return null;
    var settledAt = db.queryForList(
        "SELECT m.created_at FROM money_operations m JOIN escrows e ON e.order_id=m.order_id"
            + " JOIN money_entries debit ON debit.operation_id=m.id AND debit.account_id='PLATFORM'"
            + " JOIN money_entries credit ON credit.operation_id=m.id AND credit.account_id=?"
            + " WHERE m.order_id=? AND m.kind='SETTLEMENT' AND e.status='SETTLED'"
            + " AND m.amount=? AND e.amount=m.amount AND debit.amount=-m.amount"
            + " AND credit.amount=m.amount AND m.created_at BETWEEN ? AND ?",
        Long.class, s(order, "seller_id"), s(order, "id"), n(order, "amount"),
        n(order, "created_at"), now);
    if (settledAt.isEmpty()) return null;
    return settledAt.getFirst() + WINDOW;
  }

  private static boolean participates(String user, Map<String, Object> order) {
    return user.equals(s(order, "buyer_id")) || user.equals(s(order, "seller_id"));
  }

  private static Map<String, Object> requireOrder(Map<String, Object> order) {
    if (order == null) throw new BusinessException("NOT_FOUND", "订单不存在", 404);
    return order;
  }
}
