package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.campus.shared.Actor;
import cn.edu.campus.shared.BusinessException;
import cn.edu.campus.trade.TradeAfterSalesPort;
import cn.edu.campus.trade.TradePort;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TradePortIntegrityTest extends WorkflowTestSupport {
  private static final Actor BUYER = new Actor("buyer", "buyer", "陈同学", "USER");
  @Autowired TradePort tradePort;
  @Autowired TradeAfterSalesPort afterSales;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void paymentUsesCurrentBalanceAfterConcurrentDebit() throws Exception {
    var buyer = new Browser("buyer");
    String first = order(buyer);
    String second = order(buyer);
    db.update("UPDATE accounts SET balance=10000 WHERE user_id='buyer'");

    assertThatThrownBy(
            () ->
                payAfterConcurrentCommit(
                    second, 10_000, () -> buyer.post("/orders/" + first + "/payment", Map.of())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code).isEqualTo("INSUFFICIENT_FUNDS"));
    assertThat(balance()).isZero();
    assertThat(state(first)).isEqualTo("AWAITING_SHIPMENT");
    assertThat(state(second)).isEqualTo("PENDING_PAYMENT");
  }

  @Test
  void paymentUsesCurrentBalanceAfterConcurrentRefund() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String second = order(buyer);
    String first = paid(buyer);
    String refund = openRefund(buyer, first);
    db.update("UPDATE accounts SET balance=0 WHERE user_id='buyer'");

    payAfterConcurrentCommit(
        second,
        0,
        () ->
            seller.post(
                "/cases/" + refund + "/actions", Map.of("action", "REFUND", "reason", "同意退款")));

    assertThat(balance()).isZero();
    assertThat(state(first)).isEqualTo("CLOSED");
    assertThat(state(second)).isEqualTo("AWAITING_SHIPMENT");
  }

  @Test
  void sellerRejectionCannotReleaseShippingBeforeAppealCloses() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String order = paid(buyer);
    String refund = openRefund(buyer, order);
    seller.post("/cases/" + refund + "/actions", Map.of("action", "REJECT", "reason", "已备货"));
    String decision =
        db.queryForObject(
            "SELECT id FROM case_decisions WHERE case_id=? AND action='REJECT'",
            String.class,
            refund);
    assertThat(decision).isNotNull();

    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(status -> afterSales.applyCaseDecision(order, decision)))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code).isEqualTo("INVALID_DECISION"));
    assertThat(
            db.queryForObject(
                "SELECT shipping_blocked FROM orders WHERE id=?", Boolean.class, order))
        .isTrue();
    assertThat(
            db.queryForObject("SELECT status FROM escrows WHERE order_id=?", String.class, order))
        .isEqualTo("HELD");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM applied_decisions WHERE decision_id=?",
                Integer.class,
                decision))
        .isZero();
  }

  private String openRefund(Browser buyer, String order) throws Exception {
    return (String)
        buyer
            .post("/cases", Map.of("orderId", order, "kind", "REFUND", "reason", "申请退款"))
            .get("id");
  }

  private long balance() {
    return db.queryForObject("SELECT balance FROM accounts WHERE user_id='buyer'", Long.class);
  }

  private void payAfterConcurrentCommit(String order, long startingBalance, Callable<?> mutation) {
    var snapshot = new TransactionTemplate(transactions);
    snapshot.setIsolationLevel(TransactionTemplate.ISOLATION_REPEATABLE_READ);
    try (var worker = Executors.newSingleThreadExecutor()) {
      snapshot.executeWithoutResult(
          status -> {
            assertThat(balance()).isEqualTo(startingBalance);
            try {
              worker.submit(mutation).get(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
              throw new AssertionError("Concurrent mutation failed to commit", e);
            }
            tradePort.pay(BUYER, order);
          });
    }
  }
}
