package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerTradeNotificationTest extends ApiTestSupport {
  @Test
  void placingAndPayingNotifySellerOnceWithActionableMessages() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String productId = product();
    String key = UUID.randomUUID().toString();
    var input = Map.of("productId", productId);
    var placed = buyer.call("POST", "/orders", input, key, 200);
    String id = (String) placed.get("id");
    buyer.call("POST", "/orders", input, key, 200);
    assertThat(db.queryForList("SELECT message FROM outbox WHERE aggregate_id=? AND recipient_id='seller' AND event_type='ORDER_CREATED'", id))
        .containsExactly(Map.of("message", "有买家下单，等待付款"));
    assertThat(seller.call("GET", "/my/products", null, null, 200).toString()).contains("LOCKED");

    String payKey = UUID.randomUUID().toString();
    buyer.call("POST", "/orders/" + id + "/payment", Map.of(), payKey, 200);
    buyer.call("POST", "/orders/" + id + "/payment", Map.of(), payKey, 200);
    assertThat(db.queryForList("SELECT message FROM outbox WHERE aggregate_id=? AND recipient_id='seller' AND event_type='PAID'", id))
        .containsExactly(Map.of("message", "买家已付款，请及时发货"));
    assertThat(seller.call("GET", "/orders/" + id, null, null, 200))
        .containsEntry("status", "AWAITING_SHIPMENT");
    assertThat(db.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, productId)).isEqualTo("SOLD");
  }
}
