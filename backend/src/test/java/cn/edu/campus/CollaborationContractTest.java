package cn.edu.campus;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class CollaborationContractTest extends ApiTestSupport {
 @Test void moderationAndNotificationEndpointsRespectRoles() throws Exception {
  var admin=new Browser("admin");admin.call("GET","/moderation/products",null,null,200);admin.call("GET","/reports",null,null,200);
  var b=new Browser("buyer");b.call("GET","/moderation/products",null,null,403);b.call("GET","/notifications",null,null,200);
 }
 @Test void reportCanBeResolvedAndDemoClockIsAdminOnly() throws Exception {
  var b=new Browser("buyer");String p=product();String r=(String)b.post("/reports",Map.of("productId",p,"reason","商品描述需要核实")).get("id");
  new Browser("admin").post("/reports/"+r+"/resolve",Map.of("result","已核验并提醒卖家补充说明"));
  b.call("POST","/demo/advance",Map.of("millis",1000),UUID.randomUUID().toString(),403);
 }
}
