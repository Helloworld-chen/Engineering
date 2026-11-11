package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderParticipantsTest extends ApiTestSupport {
  @Test
  void authorizedOrderViewsIncludeBothCurrentDisplayNamesWithoutExpandingAccess() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    var placed = buyer.post("/orders", Map.of("productId", product()));
    String id = (String) placed.get("id");
    assertThat(placed).containsEntry("sellerName", "林同学").containsEntry("buyerName", "陈同学");
    for (var viewer : List.of(buyer, seller)) {
      assertThat(viewer.call("GET", "/orders/" + id, null, null, 200))
          .containsEntry("sellerName", "林同学").containsEntry("buyerName", "陈同学")
          .containsEntry("sellerId", "seller").containsEntry("buyerId", "buyer");
    }
    new Browser("other").call("GET", "/orders/" + id, null, null, 403);
    new Browser("admin").call("GET", "/orders/" + id, null, null, 403);
    var support = new Browser("support");
    support.call("GET", "/orders/" + id, null, null, 403);
    buyer.post("/orders/" + id + "/payment", Map.of());
    buyer.post("/cases", Map.of("orderId", id, "kind", "REFUND", "reason", "核对订单信息"));
    assertThat(support.call("GET", "/orders/" + id, null, null, 200))
        .containsEntry("sellerName", "林同学").containsEntry("buyerName", "陈同学");
    try {
      db.update("UPDATE users SET display_name='林同学新昵称' WHERE id='seller'");
      assertThat(buyer.call("GET", "/orders/" + id, null, null, 200))
          .containsEntry("sellerName", "林同学新昵称").containsEntry("sellerId", "seller");
    } finally {
      db.update("UPDATE users SET display_name='林同学' WHERE id='seller'");
    }
  }
}
