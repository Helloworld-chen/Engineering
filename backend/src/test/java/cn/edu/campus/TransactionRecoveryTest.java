package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.campus.notification.DeadlineRunner;
import cn.edu.campus.trade.TradeService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TransactionRecoveryTest extends WorkflowTestSupport {
  @Autowired DeadlineRunner deadlines;
  @Autowired TradeService trade;

  private int count(String sql, Object... parameters) {
    return db.queryForObject(sql, Integer.class, parameters);
  }

  private long balance(String user) {
    return db.queryForObject("SELECT balance FROM accounts WHERE user_id=?", Long.class, user);
  }

  private void failMoneyWrite(String operation) {
    db.execute(
        "ALTER TABLE money_operations ADD CONSTRAINT test_reject_money CHECK (kind <> '"
            + operation
            + "')");
  }

  private void restoreMoneyWrite() {
    db.execute("ALTER TABLE money_operations DROP CHECK test_reject_money");
  }

  @Test
  void ledgerFailureAfterDebitRollsBackAndSameKeyCanRetry() throws Exception {
    var buyer = new Browser("buyer");
    String order = order(buyer);
    String key = UUID.randomUUID().toString();
    String path = "/orders/" + order + "/payment";
    failMoneyWrite("PAYMENT");
    try {
      buyer.call("POST", path, Map.of(), key, 503);
      assertThat(state(order)).isEqualTo("PENDING_PAYMENT");
      assertThat(balance("buyer")).isEqualTo(20_000);
      assertThat(count("SELECT COUNT(*) FROM product_holds WHERE order_id=?", order)).isOne();
      assertThat(count("SELECT COUNT(*) FROM escrows WHERE order_id=?", order)).isZero();
      assertThat(
              count(
                  "SELECT COUNT(*) FROM outbox WHERE aggregate_id=? AND event_type='PAID'", order))
          .isZero();
      assertThat(count("SELECT COUNT(*) FROM request_results WHERE request_key=?", key)).isZero();
    } finally {
      restoreMoneyWrite();
    }
    buyer.call("POST", path, Map.of(), key, 200);
    assertThat(state(order)).isEqualTo("AWAITING_SHIPMENT");
    assertThat(balance("buyer")).isEqualTo(10_000);
  }

  @Test
  void refundFailureRollsBackCaseDecisionAndTradeTogether() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String order = paid(buyer);
    String refund =
        (String)
            buyer
                .post("/cases", Map.of("orderId", order, "kind", "REFUND", "reason", "申请退款"))
                .get("id");
    String key = UUID.randomUUID().toString();
    String path = "/cases/" + refund + "/actions";
    var body = Map.of("action", "REFUND", "reason", "同意退款");
    failMoneyWrite("REFUND");
    try {
      seller.call("POST", path, body, key, 503);
      assertThat(state(order)).isEqualTo("AWAITING_SHIPMENT");
      assertThat(balance("buyer")).isEqualTo(10_000);
      assertThat(
              db.queryForObject("SELECT status FROM escrows WHERE order_id=?", String.class, order))
          .isEqualTo("HELD");
      assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, refund))
          .isEqualTo("SELLER_REVIEW");
      assertThat(count("SELECT COUNT(*) FROM case_decisions WHERE case_id=?", refund)).isOne();
      assertThat(count("SELECT COUNT(*) FROM applied_decisions WHERE order_id=?", order)).isOne();
      assertThat(count("SELECT COUNT(*) FROM request_results WHERE request_key=?", key)).isZero();
    } finally {
      restoreMoneyWrite();
    }
    seller.call("POST", path, body, key, 200);
    seller.call("POST", path, body, key, 200);
    assertThat(state(order)).isEqualTo("CLOSED");
    assertThat(balance("buyer")).isEqualTo(20_000);
    assertThat(
            count(
                "SELECT COUNT(*) FROM money_operations WHERE order_id=? AND kind='REFUND'", order))
        .isOne();
  }

  @Test
  void recoveryUsesOriginalDeliveryTimeAndDrainsDependentDeadlines() throws Exception {
    var buyer = new Browser("buyer");
    String unpaid = order(buyer);
    String order = paid(buyer);
    new Browser("seller").post("/orders/" + order + "/shipment", evidence(false));
    long deliveryDue =
        db.queryForObject("SELECT due_at FROM shipments WHERE order_id=?", Long.class, order);
    db.update("UPDATE clock_state SET frozen_at=? WHERE id=1", deliveryDue + 172_800_000L);

    deadlines.recover();
    assertThat(deadlines.ready()).isTrue();
    assertThat(state(unpaid)).isEqualTo("CLOSED");
    assertThat(state(order)).isEqualTo("COMPLETED");
    assertThat(
            db.queryForObject(
                "SELECT delivered_at FROM shipments WHERE order_id=?", Long.class, order))
        .isEqualTo(deliveryDue);
    assertThat(
            db.queryForObject("SELECT receipt_deadline FROM orders WHERE id=?", Long.class, order))
        .isEqualTo(deliveryDue + 172_800_000L);
    assertThat(
            db.queryForList(
                """
                SELECT event_type FROM outbox
                WHERE aggregate_id IN (?,?) AND recipient_id='buyer'
                AND event_type IN ('DELIVERED','ORDER_CLOSED','SETTLED') ORDER BY id
                """,
                String.class,
                unpaid,
                order))
        .containsExactly("DELIVERED", "ORDER_CLOSED", "SETTLED");
    assertThat(count("SELECT COUNT(*) FROM deadlines WHERE status='PENDING'")).isZero();
  }

  @Test
  void recoveryFailureLeavesTaskPendingAndBlocksHttpMutation() throws Exception {
    var buyer = new Browser("buyer");
    String order = paid(buyer);
    new Browser("seller").post("/orders/" + order + "/shipment", evidence(false));
    advance(10_000);
    db.update("UPDATE clock_state SET frozen_at=frozen_at+172800000 WHERE id=1");
    failMoneyWrite("SETTLEMENT");
    try {
      deadlines.recover();
      assertThat(deadlines.ready()).isFalse();
      assertThat(state(order)).isEqualTo("DELIVERED");
      assertThat(balance("seller")).isEqualTo(5_000);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM deadlines WHERE kind='RECEIPT' AND status='PENDING' AND"
                      + " aggregate_id=?",
                  order))
          .isOne();
      buyer.call(
          "POST", "/orders/" + order + "/receipt", Map.of(), UUID.randomUUID().toString(), 503);
    } finally {
      restoreMoneyWrite();
      deadlines.recover();
    }
    assertThat(deadlines.ready()).isTrue();
    assertThat(state(order)).isEqualTo("COMPLETED");
    assertThat(balance("seller")).isEqualTo(15_000);
  }

  @Test
  void duplicateShipmentAndDeliveryPreserveOriginalTimestamps() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String order = paid(buyer);
    String path = "/orders/" + order + "/shipment";
    seller.post(path, evidence(false));
    long deliveryDue =
        db.queryForObject("SELECT due_at FROM shipments WHERE order_id=?", Long.class, order);
    advance(9_999);
    seller.post(path, evidence(false));
    advance(1);
    assertThat(state(order)).isEqualTo("DELIVERED");
    trade.deadline(order, "DELIVERY", 0, deliveryDue);
    assertThat(count("SELECT COUNT(*) FROM shipments WHERE order_id=?", order)).isOne();
    assertThat(
            db.queryForObject(
                "SELECT delivered_at FROM shipments WHERE order_id=?", Long.class, order))
        .isEqualTo(deliveryDue);
    assertThat(
            db.queryForObject("SELECT receipt_deadline FROM orders WHERE id=?", Long.class, order))
        .isEqualTo(deliveryDue + 172_800_000L);
    assertThat(
            count(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id=? AND event_type='DELIVERED' AND recipient_id='buyer'",
                order))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM outbox WHERE aggregate_id=? AND event_type='BUSINESS_CHANGED' AND recipient_id='seller'", order))
        .isEqualTo(2);
    assertThat(balance("seller")).isEqualTo(5_000);
  }

  @Test
  void rejectingComplaintAtZeroRemainderAllowsImmediateSettlement() throws Exception {
    var buyer = new Browser("buyer");
    String order = paid(buyer);
    new Browser("seller").post("/orders/" + order + "/shipment", evidence(false));
    advance(10_000);
    db.update("UPDATE clock_state SET frozen_at=frozen_at+172800000 WHERE id=1");
    String complaint =
        (String)
            buyer
                .post("/cases", Map.of("orderId", order, "kind", "COMPLAINT", "reason", "商品损坏"))
                .get("id");
    assertThat(
            db.queryForObject("SELECT receipt_remaining FROM orders WHERE id=?", Long.class, order))
        .isZero();
    deadlines.drain();
    assertThat(state(order)).isEqualTo("DELIVERED");
    new Browser("support")
        .post("/cases/" + complaint + "/actions", Map.of("action", "REJECT", "reason", "证据不足"));
    deadlines.drain();
    assertThat(state(order)).isEqualTo("COMPLETED");
    assertThat(balance("seller")).isEqualTo(15_000);
  }

  @Test
  void cancelledOrdersOldTaskDoesNotCloseReplacementOrder() throws Exception {
    var buyer = new Browser("buyer");
    var other = new Browser("other");
    String product = product();
    String first = (String) buyer.post("/orders", Map.of("productId", product)).get("id");
    buyer.post("/orders/" + first + "/cancel", Map.of());
    advance(1);
    String replacement = (String) other.post("/orders", Map.of("productId", product)).get("id");
    advance(119_999);
    assertThat(state(first)).isEqualTo("CLOSED");
    assertThat(state(replacement)).isEqualTo("PENDING_PAYMENT");
    assertThat(
            db.queryForObject(
                "SELECT order_id FROM product_holds WHERE product_id=?", String.class, product))
        .isEqualTo(replacement);
  }

  @Test
  void payingJustBeforeCutoffInvalidatesOldExpiryTask() throws Exception {
    var buyer = new Browser("buyer");
    String order = order(buyer);
    advance(119_999);
    buyer.post("/orders/" + order + "/payment", Map.of());
    advance(1);
    assertThat(state(order)).isEqualTo("AWAITING_SHIPMENT");
    assertThat(balance("buyer")).isEqualTo(10_000);
    assertThat(
            db.queryForObject("SELECT status FROM escrows WHERE order_id=?", String.class, order))
        .isEqualTo("HELD");
    assertThat(
            db.queryForObject(
                "SELECT sale_status FROM products WHERE id=(SELECT product_id FROM orders WHERE"
                    + " id=?)",
                String.class,
                order))
        .isEqualTo("SOLD");
  }

  @Test
  void receiptDeadlineStaysStableAcrossSessionsAndSettlesAtBoundary() throws Exception {
    var buyer = new Browser("buyer");
    String order = paid(buyer);
    String path = "/orders/" + order;
    new Browser("seller").post(path + "/shipment", evidence(false));
    advance(10_000);
    var delivered = buyer.call("GET", path, null, null, 200);
    advance(172_799_999);
    var reconnected = new Browser("buyer").call("GET", path, null, null, 200);
    assertThat(reconnected.get("status")).isEqualTo("DELIVERED");
    assertThat(reconnected.get("fundsStatus")).isEqualTo("HELD");
    assertThat(reconnected.get("receiptDeadline"))
        .isEqualTo(delivered.get("receiptDeadline"))
        .isNotNull();
    assertThat(reconnected.get("serverTime")).isNotEqualTo(delivered.get("serverTime"));
    assertThat(balance("seller")).isEqualTo(5_000);
    advance(1);
    assertThat(state(order)).isEqualTo("COMPLETED");
    assertThat(balance("seller")).isEqualTo(15_000);
  }
}
