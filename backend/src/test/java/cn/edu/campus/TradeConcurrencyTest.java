package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.campus.notification.DeadlineRunner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TradeConcurrencyTest extends WorkflowTestSupport {
  @Autowired DeadlineRunner deadlines;

  record Outcome(int status, Map<String, Object> body) {}

  private Outcome mutation(Browser browser, String path, Object body, String key) throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/api/v1" + path))
            .timeout(java.time.Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .header("X-CSRF-TOKEN", browser.csrf)
            .header("Idempotency-Key", key)
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    var response = browser.client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    return new Outcome(response.statusCode(), json.readValue(response.body(), Map.class));
  }

  private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
    var start = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var left =
          workers.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return first.call();
              });
      var right =
          workers.submit(
              () -> {
                start.await(5, TimeUnit.SECONDS);
                return second.call();
              });
      return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
    }
  }

  private int count(String sql, Object... parameters) {
    return db.queryForObject(sql, Integer.class, parameters);
  }

  private long balance(String user) {
    return db.queryForObject("SELECT balance FROM accounts WHERE user_id=?", Long.class, user);
  }

  @Test
  void onlyOneBuyerWinsTheSameProduct() throws Exception {
    var buyer = new Browser("buyer");
    var other = new Browser("other");
    String product = product();
    var body = Map.of("productId", product);
    var results =
        race(
            () -> mutation(buyer, "/orders", body, UUID.randomUUID().toString()),
            () -> mutation(other, "/orders", body, UUID.randomUUID().toString()));

    assertThat(results).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);
    var order =
        results.stream()
            .filter(result -> result.status() == 200)
            .findFirst()
            .orElseThrow()
            .body()
            .get("id");
    assertThat(count("SELECT COUNT(*) FROM orders WHERE product_id=?", product)).isOne();
    assertThat(
            db.queryForObject(
                "SELECT order_id FROM product_holds WHERE product_id=?", String.class, product))
        .isEqualTo(order);
  }

  @Test
  void simultaneousSameKeyReturnsSamePaymentWithBalancedLedger() throws Exception {
    var buyer = new Browser("buyer");
    var anotherSession = new Browser("buyer");
    String order = order(buyer);
    String key = UUID.randomUUID().toString();
    String path = "/orders/" + order + "/payment";
    var results =
        race(
            () -> mutation(buyer, path, Map.of(), key),
            () -> mutation(anotherSession, path, Map.of(), key));

    assertThat(results).extracting(Outcome::status).containsExactly(200, 200);
    assertThat(results.getFirst().body()).isEqualTo(results.getLast().body());
    assertThat(balance("buyer")).isEqualTo(10_000);
    assertThat(
            count(
                "SELECT COUNT(*) FROM money_operations WHERE order_id=? AND kind='PAYMENT'", order))
        .isOne();
    assertThat(
            db.queryForList(
                """
                SELECT e.amount FROM money_entries e JOIN money_operations m ON m.id=e.operation_id
                WHERE m.order_id=? ORDER BY e.amount
                """,
                Long.class,
                order))
        .containsExactly(-10_000L, 10_000L);
  }

  @Test
  void paymentAtCutoffCannotWinAgainstExpiry() throws Exception {
    var buyer = new Browser("buyer");
    String product = product();
    String order = (String) buyer.post("/orders", Map.of("productId", product)).get("id");
    db.update("UPDATE clock_state SET frozen_at=frozen_at+120000 WHERE id=1");
    var results =
        race(
            () ->
                mutation(
                        buyer,
                        "/orders/" + order + "/payment",
                        Map.of(),
                        UUID.randomUUID().toString())
                    .status(),
            () -> {
              deadlines.drain();
              return 200;
            });

    assertThat(results.getFirst()).isEqualTo(409);
    assertThat(state(order)).isEqualTo("CLOSED");
    assertThat(db.queryForObject("SELECT close_reason FROM orders WHERE id=?", String.class, order))
        .isEqualTo("PAYMENT_EXPIRED");
    assertThat(count("SELECT COUNT(*) FROM product_holds WHERE product_id=?", product)).isZero();
    assertThat(balance("buyer")).isEqualTo(20_000);
    assertThat(count("SELECT COUNT(*) FROM escrows WHERE order_id=?", order)).isZero();
  }

  @Test
  void manualAndAutomaticReceiptCanSettleOnlyOnce() throws Exception {
    var buyer = new Browser("buyer");
    String order = paid(buyer);
    new Browser("seller").post("/orders/" + order + "/shipment", evidence(false));
    advance(10_000);
    db.update("UPDATE clock_state SET frozen_at=frozen_at+172800000 WHERE id=1");
    var results =
        race(
            () ->
                mutation(
                        buyer,
                        "/orders/" + order + "/receipt",
                        Map.of(),
                        UUID.randomUUID().toString())
                    .status(),
            () -> {
              deadlines.drain();
              return 200;
            });

    assertThat(results.getFirst()).isIn(200, 409);
    assertThat(state(order)).isEqualTo("COMPLETED");
    assertThat(balance("seller")).isEqualTo(15_000);
    assertThat(
            count(
                "SELECT COUNT(*) FROM money_operations WHERE order_id=? AND kind='SETTLEMENT'",
                order))
        .isOne();
  }

  @Test
  void refundRequestAndShipmentHaveExclusiveOutcomes() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String order = paid(buyer);
    var shipping = evidence(false);
    var results =
        race(
            () ->
                mutation(
                    buyer,
                    "/cases",
                    Map.of("orderId", order, "kind", "REFUND", "reason", "申请退款"),
                    UUID.randomUUID().toString()),
            () ->
                mutation(
                    seller,
                    "/orders/" + order + "/shipment",
                    shipping,
                    UUID.randomUUID().toString()));

    assertThat(results).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);
    boolean refundOpened = results.getFirst().status() == 200;
    assertThat(state(order)).isEqualTo(refundOpened ? "AWAITING_SHIPMENT" : "IN_TRANSIT");
    assertThat(count("SELECT COUNT(*) FROM cases WHERE order_id=?", order))
        .isEqualTo(refundOpened ? 1 : 0);
    assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id=?", order))
        .isEqualTo(refundOpened ? 0 : 1);
    assertThat(
            db.queryForObject(
                "SELECT shipping_blocked FROM orders WHERE id=?", Boolean.class, order))
        .isEqualTo(refundOpened);
  }

  @Test
  void complaintAndSettlementCannotBothSucceed() throws Exception {
    var buyer = new Browser("buyer");
    var anotherSession = new Browser("buyer");
    String order = paid(buyer);
    new Browser("seller").post("/orders/" + order + "/shipment", evidence(false));
    advance(10_000);
    var results =
        race(
            () ->
                mutation(
                    buyer,
                    "/cases",
                    Map.of("orderId", order, "kind", "COMPLAINT", "reason", "商品损坏"),
                    UUID.randomUUID().toString()),
            () ->
                mutation(
                    anotherSession,
                    "/orders/" + order + "/receipt",
                    Map.of(),
                    UUID.randomUUID().toString()));

    assertThat(results).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);
    boolean complaintOpened = results.getFirst().status() == 200;
    assertThat(state(order)).isEqualTo(complaintOpened ? "DELIVERED" : "COMPLETED");
    assertThat(count("SELECT COUNT(*) FROM cases WHERE order_id=?", order))
        .isEqualTo(complaintOpened ? 1 : 0);
    assertThat(balance("seller")).isEqualTo(complaintOpened ? 5_000 : 15_000);
    assertThat(
            db.queryForObject("SELECT status FROM escrows WHERE order_id=?", String.class, order))
        .isEqualTo(complaintOpened ? "HELD" : "SETTLED");
  }

  @Test
  void simultaneousEvidenceReuseLinksFilesToOnlyOneOrder() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    var secondSession = new Browser("seller");
    String first = paid(buyer);
    String second = paid(buyer);
    var evidence = evidence(false);
    var results =
        race(
            () ->
                mutation(
                    seller,
                    "/orders/" + first + "/shipment",
                    evidence,
                    UUID.randomUUID().toString()),
            () ->
                mutation(
                    secondSession,
                    "/orders/" + second + "/shipment",
                    evidence,
                    UUID.randomUUID().toString()));

    assertThat(results).extracting(Outcome::status).containsExactlyInAnyOrder(200, 409);
    String winner = results.getFirst().status() == 200 ? first : second;
    String loser = winner.equals(first) ? second : first;
    assertThat(state(winner)).isEqualTo("IN_TRANSIT");
    assertThat(state(loser)).isEqualTo("AWAITING_SHIPMENT");
    assertThat(db.queryForList("SELECT linked_id FROM files ORDER BY purpose", String.class))
        .containsExactly(winner, winner);
    assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id IN (?,?)", first, second))
        .isOne();
  }

  @Test
  void simultaneousSameKeyCreatesOnlyOneOrder() throws Exception {
    var buyer = new Browser("buyer");
    var secondSession = new Browser("buyer");
    String product = product();
    var body = Map.of("productId", product);
    String key = UUID.randomUUID().toString();
    var results =
        race(
            () -> mutation(buyer, "/orders", body, key),
            () -> mutation(secondSession, "/orders", body, key));

    assertThat(results).extracting(Outcome::status).containsExactly(200, 200);
    assertThat(results.getFirst().body()).isEqualTo(results.getLast().body());
    assertThat(count("SELECT COUNT(*) FROM orders WHERE product_id=?", product)).isOne();
  }
}
