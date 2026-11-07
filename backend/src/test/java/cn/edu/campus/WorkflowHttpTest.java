package cn.edu.campus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WorkflowHttpTest extends WorkflowTestSupport {
 @Test void deliveryBoundaryThenReceiptOnlySettlesOnce()throws Exception {
  var b=new Browser("buyer");String id=paid(b);var seller=new Browser("seller");seller.post("/orders/"+id+"/shipment",evidence(false));
  b.call("POST","/orders/"+id+"/receipt",Map.of(),UUID.randomUUID().toString(),409);
  advance(9999);assertThat(state(id)).isEqualTo("IN_TRANSIT");advance(1);assertThat(state(id)).isEqualTo("DELIVERED");
  assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='seller'",Long.class)).isEqualTo(5000);
  String key=UUID.randomUUID().toString();b.call("POST","/orders/"+id+"/receipt",Map.of(),key,200);b.call("POST","/orders/"+id+"/receipt",Map.of(),key,200);
  assertThat(state(id)).isEqualTo("COMPLETED");assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='seller'",Long.class)).isEqualTo(15000);
 }
 @Test void complaintPauseResumeInvalidatesOldDeadline()throws Exception {
  var b=new Browser("buyer");String id=paid(b);new Browser("seller").post("/orders/"+id+"/shipment",evidence(false));advance(10000+20*3600000L);
  String c=(String)b.post("/cases",Map.of("orderId",id,"kind","COMPLAINT","reason","与描述不符")).get("id");
  assertThat(db.queryForObject("SELECT receipt_remaining FROM orders WHERE id=?",Long.class,id)).isEqualTo(28*3600000L);
  advance(5*3600000L);new Browser("support").post("/cases/"+c+"/actions",Map.of("action","REJECT","reason","核验后驳回"));
  advance(23*3600000L);assertThat(state(id)).isEqualTo("DELIVERED");advance(5*3600000L);assertThat(state(id)).isEqualTo("COMPLETED");
 }
 @Test void approvedReturnTimesOutToSupportWithoutRefund()throws Exception {
  var b=new Browser("buyer");String id=paid(b);new Browser("seller").post("/orders/"+id+"/shipment",evidence(false));advance(10000);
  String c=(String)b.post("/cases",Map.of("orderId",id,"kind","COMPLAINT","reason","商品损坏")).get("id");new Browser("support").post("/cases/"+c+"/actions",Map.of("action","APPROVE_RETURN","reason","支持退货"));b.post("/orders/"+id+"/return-shipment",evidence(true));
  advance(9999);assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?",String.class,c)).isEqualTo("RETURN_IN_TRANSIT");advance(1);
  assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?",String.class,c)).isEqualTo("RETURN_INSPECTION");
  advance(86_399_999);
  assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?",String.class,c)).isEqualTo("RETURN_INSPECTION");
  advance(1);
  assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?",String.class,c)).isEqualTo("SUPPORT_REVIEW");assertThat(db.queryForObject("SELECT status FROM escrows WHERE order_id=?",String.class,id)).isEqualTo("HELD");
  new Browser("support").post("/cases/"+c+"/actions",Map.of("action","REFUND","reason","退货证据已核验"));assertThat(db.queryForObject("SELECT status FROM escrows WHERE order_id=?",String.class,id)).isEqualTo("REFUNDED");
 }
 @Test void preShipmentRefundBlocksShippingAndCanAppeal()throws Exception {
  var b=new Browser("buyer");String id=paid(b);String c=(String)b.post("/cases",Map.of("orderId",id,"kind","REFUND","reason","暂不需要商品")).get("id");
  new Browser("seller").call("POST","/orders/"+id+"/shipment",evidence(false),UUID.randomUUID().toString(),409);
  new Browser("seller").post("/cases/"+c+"/actions",Map.of("action","REJECT","reason","已备货"));b.post("/cases/"+c+"/actions",Map.of("action","APPEAL","reason","请求客服处理"));
  new Browser("support").post("/cases/"+c+"/actions",Map.of("action","REJECT","reason","不支持退款"));assertThat(db.queryForObject("SELECT shipping_blocked FROM orders WHERE id=?",Boolean.class,id)).isFalse();
 }
    @Test void repeatingComplaintAndRejectionPreservesGenerationsAndDeadlines() throws Exception {
        var buyer = new Browser("buyer");
        var support = new Browser("support");
        String order = paid(buyer);
        new Browser("seller").post("/orders/" + order + "/shipment", evidence(false));
        advance(10_000 + 20 * 3_600_000L);
        var complaint = Map.of("orderId", order, "kind", "COMPLAINT", "reason", "商品损坏");
        String openKey = UUID.randomUUID().toString();
        var opened = buyer.call("POST", "/cases", complaint, openKey, 200);
        var paused = db.queryForMap("SELECT receipt_remaining,receipt_generation FROM orders WHERE id=?", order);
        advance(5 * 3_600_000L);
        assertThat(buyer.call("POST", "/cases", complaint, openKey, 200)).isEqualTo(opened);
        assertThat(db.queryForMap("SELECT receipt_remaining,receipt_generation FROM orders WHERE id=?", order)).isEqualTo(paused);

        String path = "/cases/" + opened.get("id") + "/actions";
        String rejectKey = UUID.randomUUID().toString();
        var rejection = Map.of("action", "REJECT", "reason", "证据不足");
        var rejected = support.call("POST", path, rejection, rejectKey, 200);
        var resumed = db.queryForMap("SELECT receipt_deadline,receipt_generation FROM orders WHERE id=?", order);
        advance(1_000);
        assertThat(support.call("POST", path, rejection, rejectKey, 200)).isEqualTo(rejected);
        assertThat(db.queryForMap("SELECT receipt_deadline,receipt_generation FROM orders WHERE id=?", order)).isEqualTo(resumed);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM applied_decisions WHERE order_id=?", Integer.class, order)).isEqualTo(2);
    }

    @Test void sellerAcceptsDeliveredReturnAndOriginalFundsAreRefundedOnce() throws Exception {
        var buyer = new Browser("buyer");
        var seller = new Browser("seller");
        String order = paid(buyer);
        seller.post("/orders/" + order + "/shipment", evidence(false));
        advance(10_000);
        String complaint = (String) buyer.post("/cases", Map.of("orderId", order, "kind", "COMPLAINT", "reason", "商品损坏")).get("id");
        String path = "/cases/" + complaint + "/actions";
        new Browser("support").post(path, Map.of("action", "APPROVE_RETURN", "reason", "支持退货"));
        buyer.post("/orders/" + order + "/return-shipment", evidence(true));
        var acceptance = Map.of("action", "ACCEPT_RETURN", "reason", "已验收退货");
        seller.call("POST", path, acceptance, UUID.randomUUID().toString(), 409);
        advance(10_000);
        String key = UUID.randomUUID().toString();
        var refunded = seller.call("POST", path, acceptance, key, 200);
        assertThat(seller.call("POST", path, acceptance, key, 200)).isEqualTo(refunded);
        assertThat(state(order)).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT status FROM escrows WHERE order_id=?", String.class, order)).isEqualTo("REFUNDED");
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='buyer'", Long.class)).isEqualTo(20_000);
        assertThat(db.queryForObject("SELECT balance FROM accounts WHERE user_id='seller'", Long.class)).isEqualTo(5_000);
        assertThat(db.queryForList("SELECT amount FROM money_operations WHERE order_id=? AND kind='REFUND'", Long.class, order)).containsExactly(10_000L);
        buyer.call("POST", "/orders/" + order + "/receipt", Map.of(), UUID.randomUUID().toString(), 409);
    }

    @Test void refundReviewAndBuyerAppealFollowTheirOwnTimeouts() throws Exception {
        var buyer = new Browser("buyer");
        var seller = new Browser("seller");
        String first = paid(buyer);
        String second = paid(buyer);
        String expired = (String) buyer.post("/cases", Map.of("orderId", first, "kind", "REFUND", "reason", "申请退款")).get("id");
        String rejected = (String) buyer.post("/cases", Map.of("orderId", second, "kind", "REFUND", "reason", "申请退款")).get("id");
        seller.post("/cases/" + rejected + "/actions", Map.of("action", "REJECT", "reason", "已备货"));
        advance(86_399_999);
        assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, expired)).isEqualTo("SELLER_REVIEW");
        assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, rejected)).isEqualTo("BUYER_APPEAL");
        advance(1);
        assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, expired)).isEqualTo("SUPPORT_REVIEW");
        assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, rejected)).isEqualTo("CLOSED");
        assertThat(db.queryForObject("SELECT shipping_blocked FROM orders WHERE id=?", Boolean.class, first)).isTrue();
        assertThat(db.queryForObject("SELECT shipping_blocked FROM orders WHERE id=?", Boolean.class, second)).isFalse();
    }
}
