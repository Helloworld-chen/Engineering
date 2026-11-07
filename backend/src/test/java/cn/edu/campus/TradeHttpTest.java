package cn.edu.campus;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TradeHttpTest extends ApiTestSupport {
  @Test
  void paymentIsAtomicAndDuplicateReturnsSameResult() throws Exception {
    var b = new Browser("buyer");
    String id = order(b), key = UUID.randomUUID().toString();
    b.call("POST", "/orders/" + id + "/payment", Map.of(), key, 200);
    b.call("POST", "/orders/" + id + "/payment", Map.of(), key, 200);
    assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='buyer'", Long.class))
        .isEqualTo(10000);
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM money_operations WHERE order_id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(db.queryForObject("SELECT status FROM escrows WHERE order_id=?", String.class, id))
        .isEqualTo("HELD");
  }

  @Test
  void cutoffAndInsufficientBalanceNeverDebit() throws Exception {
    var b = new Browser("buyer");
    String id = order(b);
    db.update("UPDATE accounts SET balance=9999 WHERE user_id='buyer'");
    b.call("POST", "/orders/" + id + "/payment", Map.of(), UUID.randomUUID().toString(), 409);
    db.update("UPDATE accounts SET balance=20000 WHERE user_id='buyer'");
    db.update("UPDATE clock_state SET frozen_at=frozen_at+120000 WHERE id=1");
    b.call("POST", "/orders/" + id + "/payment", Map.of(), UUID.randomUUID().toString(), 409);
    assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='buyer'", Long.class))
        .isEqualTo(20000);
  }

  @Test
  void cancelReleasesOnlyOwnHoldAndCannotAccessAnotherOrder() throws Exception {
    var b = new Browser("buyer");
    String id = order(b);
    var outsider = new Browser("other");
    outsider.call("POST", "/orders/" + id + "/cancel", Map.of(), UUID.randomUUID().toString(), 403);
    b.post("/orders/" + id + "/cancel", Map.of());
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM product_holds WHERE order_id=?", Integer.class, id))
        .isZero();
  }

  @Test
  void sameKeyDifferentPayloadRejectedAndOneProductOnlyOneOrder() throws Exception {
    var b = new Browser("buyer");
    String p = product(), key = UUID.randomUUID().toString();
    b.call("POST", "/orders", Map.of("productId", p), key, 200);
    b.call("POST", "/orders", Map.of("productId", product()), key, 409);
    new Browser("other")
        .call("POST", "/orders", Map.of("productId", p), UUID.randomUUID().toString(), 409);
  }
}
