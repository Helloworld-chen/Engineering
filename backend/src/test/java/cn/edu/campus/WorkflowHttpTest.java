package cn.edu.campus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class WorkflowHttpTest extends ApiTestSupport {
 String file(String owner,String purpose){String id=UUID.randomUUID().toString();db.update("INSERT INTO files(id,owner_id,purpose,mime,filename,storage_name,byte_size,created_at) VALUES(?,?,?,'image/png','test.png',?,1,0)",id,owner,purpose,id+".png");return id;}
 Map<String,Object> evidence(boolean returning){return Map.of("trackingNumber","DEMO001","goods",List.of(file(returning?"buyer":"seller",returning?"RETURN_GOODS":"GOODS")),"packaging",List.of(file(returning?"buyer":"seller",returning?"RETURN_PACKAGING":"PACKAGING")));}
 String paid(Browser b)throws Exception{String id=order(b);b.post("/orders/"+id+"/payment",Map.of());return id;}
 void advance(long millis)throws Exception{new Browser("admin").post("/demo/advance",Map.of("millis",millis));}
 String state(String id){return db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id);}
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
  advance(9999);assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?",String.class,c)).isEqualTo("RETURN_IN_TRANSIT");advance(1+86400000L);
  assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?",String.class,c)).isEqualTo("SUPPORT_REVIEW");assertThat(db.queryForObject("SELECT status FROM escrows WHERE order_id=?",String.class,id)).isEqualTo("HELD");
  new Browser("support").post("/cases/"+c+"/actions",Map.of("action","REFUND","reason","退货证据已核验"));assertThat(db.queryForObject("SELECT status FROM escrows WHERE order_id=?",String.class,id)).isEqualTo("REFUNDED");
 }
 @Test void preShipmentRefundBlocksShippingAndCanAppeal()throws Exception {
  var b=new Browser("buyer");String id=paid(b);String c=(String)b.post("/cases",Map.of("orderId",id,"kind","REFUND","reason","暂不需要商品")).get("id");
  new Browser("seller").call("POST","/orders/"+id+"/shipment",evidence(false),UUID.randomUUID().toString(),409);
  new Browser("seller").post("/cases/"+c+"/actions",Map.of("action","REJECT","reason","已备货"));b.post("/cases/"+c+"/actions",Map.of("action","APPEAL","reason","请求客服处理"));
  new Browser("support").post("/cases/"+c+"/actions",Map.of("action","REJECT","reason","不支持退款"));assertThat(db.queryForObject("SELECT shipping_blocked FROM orders WHERE id=?",Boolean.class,id)).isFalse();
 }
}
