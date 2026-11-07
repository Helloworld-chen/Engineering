package cn.edu.campus;

import java.util.*;

abstract class WorkflowTestSupport extends ApiTestSupport {
 String file(String owner,String purpose){String id=UUID.randomUUID().toString();db.update("INSERT INTO files(id,owner_id,purpose,mime,filename,storage_name,byte_size,created_at) VALUES(?,?,?,'image/png','test.png',?,1,0)",id,owner,purpose,id+".png");return id;}
 Map<String,Object> evidence(boolean returning){return Map.of("trackingNumber","DEMO001","goods",List.of(file(returning?"buyer":"seller",returning?"RETURN_GOODS":"GOODS")),"packaging",List.of(file(returning?"buyer":"seller",returning?"RETURN_PACKAGING":"PACKAGING")));}
 String paid(Browser b)throws Exception{String id=order(b);b.post("/orders/"+id+"/payment",Map.of());return id;}
 void advance(long millis)throws Exception{new Browser("admin").post("/demo/advance",Map.of("millis",millis));}
 String state(String id){return db.queryForObject("SELECT status FROM orders WHERE id=?",String.class,id);}
}
