package cn.edu.campus;

import static org.assertj.core.api.Assertions.*;

import cn.edu.campus.notification.NotificationPort;
import cn.edu.campus.notification.TaskRuntimePort;
import cn.edu.campus.notification.TimePort;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderReviewTest extends WorkflowTestSupport {
  private static final long WINDOW = 604800000L;
  @Autowired TimePort clock;
  @Autowired TaskRuntimePort runtime;
  @Autowired NotificationPort notifications;

  String completed(Browser buyer, Browser seller) throws Exception {
    String id = paid(buyer);
    seller.post("/orders/" + id + "/shipment", evidence(false));
    advanceClock(10000L);
    buyer.post("/orders/" + id + "/receipt", Map.of());
    return id;
  }

  void advanceClock(long millis) throws Exception {
    db.update("UPDATE clock_state SET frozen_at=frozen_at+? WHERE id=1", millis);
    runtime.getClass().getMethod("recover").invoke(runtime);
    assertThat(runtime.ready()).isTrue();
  }

  String path(String id) {
    return "/orders/" + id + "/reviews";
  }

  Map<String, Object> snapshot(Browser browser, String id) throws Exception {
    return browser.call("GET", path(id), null, null, 200);
  }

  Map<String, Object> submit(Browser browser, String id, int stars, String comment) throws Exception {
    return browser.post(path(id), Map.of("stars", stars, "comment", comment));
  }

  Map<?, ?> credit(Browser browser, String user, String role, String order) throws Exception {
    return (Map<?, ?>) browser.call("GET", "/users/" + user + "/profile?contextType=order&contextId=" + order, null, null, 200).get(role + "Credit");
  }

  long countSignals(String id, String type) {
    return db.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id=? AND event_type=?", Long.class, id, type);
  }

  @Test void hiddenReviewsStayPrivateUntilBothSubmit() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    var support = new Browser("support");
    String id = completed(buyer, seller);
    assertThat(snapshot(buyer, id)).containsEntry("eligible", true)
        .containsEntry("deadline", cn.edu.campus.shared.Rows.instant(clock.now() + WINDOW));
    support.call("GET", path(id), null, null, 403);
    db.update("INSERT INTO cases(id,order_id,buyer_id,kind,reason,status,created_at) VALUES(?,?,'buyer','COMPLAINT','测试','CLOSED',?)",
        UUID.randomUUID().toString(), id, clock.now());
    new Browser("admin").call("GET", path(id), null, null, 403);
    new Browser("other").call("GET", path(id), null, null, 403);
    String key = UUID.randomUUID().toString();
    var input = Map.of("stars", 5, "comment", "  评价买家隐藏前保密  ");
    var submitted = seller.call("POST", path(id), input, key, 200);
    assertThat(submitted).containsEntry("eligible", false).containsEntry("items", List.of());
    assertThat((Map<?, ?>) submitted.get("ownReview"))
        .isEqualTo(cn.edu.campus.shared.Rows.map("id", ((Map<?, ?>) submitted.get("ownReview")).get("id"),
            "stars", 5, "comment", "评价买家隐藏前保密", "submittedAt", submitted.get("serverTime"), "publishedAt", null));
    assertThat(seller.call("POST", path(id), input, key, 200)).isEqualTo(submitted);
    seller.call("POST", path(id), input, UUID.randomUUID().toString(), 409);
    assertThat(snapshot(buyer, id)).containsEntry("eligible", true).containsEntry("ownReview", null).containsEntry("items", List.of());
    assertThat(snapshot(support, id)).containsEntry("eligible", false).containsEntry("ownReview", null).containsEntry("items", List.of());
    assertThat(credit(seller, "buyer", "buyer", id).get("count")).isEqualTo(0);
    assertThat(seller.call("GET", "/users/buyer/reviews?contextType=order&contextId=" + id, null, null, 200)).containsEntry("total", 0);
    assertThat(db.queryForList("SELECT recipient_id FROM outbox WHERE aggregate_id=? AND event_type='BUSINESS_CHANGED' AND id > (SELECT MAX(id) FROM outbox WHERE aggregate_id=? AND event_type='SETTLED')", String.class, id, id)).containsExactly("seller");
    assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isZero();

    var published = submit(buyer, id, 4, "评价卖家");
    var items = (List<?>) published.get("items");
    assertThat(items).hasSize(2);
    for (Object item : items)
      assertThat(((Map<?, ?>) item).keySet().stream().map(Object::toString)).containsExactlyInAnyOrder("id", "reviewerName", "reviewerRole", "subjectRole", "stars", "comment", "publishedAt", "productTitle");
    assertThat(snapshot(support, id)).containsEntry("ownReview", null).containsEntry("items", items);
    assertThat(credit(buyer, "seller", "seller", id).get("grade")).isEqualTo("良好");
    assertThat(credit(buyer, "seller", "buyer", id).get("grade")).isEqualTo("暂无评价");
    assertThat(credit(seller, "buyer", "buyer", id).get("grade")).isEqualTo("优秀");
    assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isEqualTo(2);
    assertThat(db.queryForList("SELECT recipient_id FROM outbox WHERE aggregate_id=? AND event_type='REVIEWS_PUBLISHED'", String.class, id)).containsExactlyInAnyOrder("buyer", "seller");
    advanceClock(WINDOW);
    assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isEqualTo(2);
    notifications.getClass().getMethod("dispatch").invoke(notifications);
    notifications.getClass().getMethod("dispatch").invoke(notifications);
    assertThat(db.queryForList("SELECT n.recipient_id FROM notifications n JOIN outbox e ON e.id=n.id"
        + " WHERE n.aggregate_id=? AND e.event_type='REVIEWS_PUBLISHED' AND n.message='订单评价已公开'",
        String.class, id)).containsExactlyInAnyOrder("buyer", "seller");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM notifications n JOIN outbox e ON e.id=n.id"
        + " WHERE n.aggregate_id=? AND e.event_type='BUSINESS_CHANGED'", Integer.class, id)).isZero();
  }

  @Test void cutoffPublishesOneExistingReviewAndRejectsNewSubmissions() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String id = completed(buyer, seller);
    advanceClock(WINDOW - 1);
    assertThat(submit(buyer, id, 3, "单方评价")).containsEntry("items", List.of());
    advanceClock(1);
    var own = snapshot(buyer, id);
    assertThat(((List<?>) own.get("items"))).hasSize(1);
    assertThat(((Map<?, ?>) own.get("ownReview")).get("publishedAt")).isEqualTo(own.get("deadline"));
    assertThat(credit(buyer, "seller", "seller", id).get("grade")).isEqualTo("一般");
    assertThat(seller.call("POST", path(id), Map.of("stars", 5, "comment", "超时"), UUID.randomUUID().toString(), 409))
        .containsEntry("code", "REVIEW_EXPIRED");
    advanceClock(2);
    assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isEqualTo(2);
  }

  @Test void historicalEligibilityUsesVerifiedSettlementAndRejectsBadInput() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String id = completed(buyer, seller);
    var csrf = buyer.csrf;
    try {
      buyer.csrf = null;
      buyer.call("POST", path(id), Map.of("stars", 5, "comment", "无令牌"),
          UUID.randomUUID().toString(), 403);
    } finally {
      buyer.csrf = csrf;
    }
    long settledAt = db.queryForObject("SELECT created_at FROM money_operations WHERE order_id=? AND kind='SETTLEMENT'", Long.class, id);
    long orderCreatedAt = db.queryForObject("SELECT created_at FROM orders WHERE id=?", Long.class, id);
    db.update("UPDATE money_operations SET created_at=? WHERE order_id=? AND kind='SETTLEMENT'", orderCreatedAt, id);
    assertThat(snapshot(buyer, id)).containsEntry("deadline", cn.edu.campus.shared.Rows.instant(orderCreatedAt + WINDOW));
    for (var invalid : List.of(Map.of("stars", 6, "comment", "超界"), Map.of("stars", 1.5, "comment", "非整数"),
        Map.of("stars", "5", "comment", "非数字"), Map.of("stars", 4, "comment", "  "),
        Map.of("stars", 4, "comment", "很".repeat(501)), Map.of("stars", 4, "comment", "内容", "subjectId", "other")))
      buyer.call("POST", path(id), invalid, UUID.randomUUID().toString(), 400);
    db.update("UPDATE money_operations SET created_at=? WHERE order_id=? AND kind='SETTLEMENT'", settledAt + 1, id);
    assertThat(snapshot(buyer, id)).containsEntry("eligible", false).containsEntry("deadline", null);
    db.update("DELETE FROM money_entries WHERE operation_id IN (SELECT id FROM money_operations WHERE order_id=? AND kind='SETTLEMENT')", id);
    db.update("DELETE FROM money_operations WHERE order_id=? AND kind='SETTLEMENT'", id);
    assertThat(snapshot(buyer, id)).containsEntry("eligible", false).containsEntry("deadline", null);
    assertThat(buyer.call("POST", path(id), Map.of("stars", 5, "comment", "缺少结算"), UUID.randomUUID().toString(), 409))
        .containsEntry("code", "REVIEW_NOT_ELIGIBLE");
  }

  @Test void validatesStarsAtOriginalJsonDecimalPrecision() throws Exception {
    var buyer = new Browser("buyer");
    String id = completed(buyer, new Browser("seller"));
    for (String fractional : List.of("4.00000000000000001", "5.00000000000000001", "0.99999999999999999")) {
      buyer.call("POST", path(id), Map.of("stars", new BigDecimal(fractional), "comment", "评分检查"),
          UUID.randomUUID().toString(), 400);
    }
    assertThat(snapshot(buyer, id)).containsEntry("ownReview", null);
    var result = buyer.post(path(id), Map.of("stars", new BigDecimal("5.0"), "comment", "整数评分"));
    assertThat(((Map<?, ?>) result.get("ownReview")).get("stars")).isEqualTo(5);
  }

  @Test void trimsBrowserWhitespaceAtCommentEdgesAndPreservesInterior() throws Exception {
    var buyer = new Browser("buyer");
    String id = completed(buyer, new Browser("seller"));
    buyer.call("POST", path(id), Map.of("stars", 4, "comment", "\u00a0\u202f\ufeff\t"),
        UUID.randomUUID().toString(), 400);
    var result = submit(buyer, id, 4, "\u00a0商品\u00a0描述  准确\ufeff");
    assertThat(((Map<?, ?>) result.get("ownReview")).get("comment")).isEqualTo("商品\u00a0描述  准确");
  }

  @Test void publishedCreditRoundsBeforeGradingAndUsesStableFilteredPagination() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String id = completed(buyer, seller);
    var orders = new ArrayList<Object[]>();
    var reviews = new ArrayList<Object[]>();
    long now = clock.now();
    for (int i = 0; i < 201; i++) {
      String order = UUID.randomUUID().toString();
      orders.add(new Object[] {order, id});
      // (99 × 5 + 101 × 4) ÷ 200 = 4.495. The final hidden 1-star row must not count.
      reviews.add(new Object[] {
          UUID.randomUUID().toString(), order, i == 200 ? 1 : i < 99 ? 5 : 4,
          "评价" + i, now, i == 200 ? null : now - 201 + i
      });
    }
    db.batchUpdate(
        "INSERT INTO orders(id,product_id,buyer_id,seller_id,amount,status,created_at,pay_deadline)"
            + " SELECT ?,product_id,buyer_id,seller_id,amount,status,created_at,pay_deadline"
            + " FROM orders WHERE id=?", orders);
    db.batchUpdate(
        "INSERT INTO order_reviews(id,order_id,reviewer_id,subject_id,subject_role,stars,comment,"
            + "submitted_at,published_at) VALUES(?,?,'buyer','seller','SELLER',?,?,?,?)", reviews);
    var stats = credit(buyer, "seller", "seller", id);
    assertThat(stats.get("count")).isEqualTo(200);
    assertThat(stats.get("average")).isEqualTo(4.5);
    assertThat(stats.get("grade")).isEqualTo("优秀");
    assertThat(stats.get("distribution")).isEqualTo(Map.of("1", 0, "2", 0, "3", 0, "4", 101, "5", 99));
    String list = "/users/seller/reviews?contextType=order&contextId=" + id;
    var page = buyer.call("GET", list + "&role=SELLER&page=2", null, null, 200);
    assertThat(page).containsEntry("total", 200).containsEntry("page", 2).containsEntry("pageSize", 20);
    var items = (List<?>) page.get("items");
    assertThat(items).hasSize(20);
    assertThat(((Map<?, ?>) items.getFirst()).get("comment")).isEqualTo("评价179");
    assertThat(((Map<?, ?>) items.getLast()).get("comment")).isEqualTo("评价160");
    assertThat(buyer.call("GET", list + "&role=BUYER", null, null, 200))
        .containsEntry("total", 0).containsEntry("items", List.of());
  }

  @Test void competingSubmissionsAreSerializedAndPublishOnce() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String id = completed(buyer, seller);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = pool.submit(() -> { start.await(); return submit(buyer, id, 4, "买家评价"); });
      var second = pool.submit(() -> { start.await(); return submit(seller, id, 2, "卖家评价"); });
      start.countDown();
      assertThat(List.of(((List<?>) first.get(20, TimeUnit.SECONDS).get("items")).size(),
          ((List<?>) second.get(20, TimeUnit.SECONDS).get("items")).size())).containsExactlyInAnyOrder(0, 2);
    }
    assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isEqualTo(2);
    assertThat(credit(seller, "buyer", "buyer", id).get("grade")).isEqualTo("待提升");
  }

  @Test void failedPublishRollsBackSecondReviewAndCanRetryWithSameKey() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String id = completed(buyer, seller);
    submit(buyer, id, 4, "先评");
    String key = UUID.randomUUID().toString();
    var body = Map.of("stars", 5, "comment", "后评");
    db.execute("ALTER TABLE outbox ADD CONSTRAINT reject_review_notice"
        + " CHECK (event_type<>'REVIEWS_PUBLISHED' OR recipient_id<>'seller')");
    try {
      seller.call("POST", path(id), body, key, 503);
      assertThat(snapshot(seller, id)).containsEntry("ownReview", null).containsEntry("items", List.of());
      assertThat(((Map<?, ?>) snapshot(buyer, id).get("ownReview")).get("publishedAt")).isNull();
      assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isZero();
    } finally {
      db.execute("ALTER TABLE outbox DROP CHECK reject_review_notice");
    }
    assertThat((List<?>) seller.call("POST", path(id), body, key, 200).get("items")).hasSize(2);
    assertThat(countSignals(id, "REVIEWS_PUBLISHED")).isEqualTo(2);
  }
}
