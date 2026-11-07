package cn.edu.campus.product;
import cn.edu.campus.shared.*;
import cn.edu.campus.files.FilePort;
import cn.edu.campus.notification.TimePort;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
import static cn.edu.campus.shared.BusinessException.*;
@Service @ConditionalOnProperty(name="market.providers.product",havingValue="demo")
public class DemoProductAdapter implements ProductPort {
 private final JdbcTemplate db;private final TimePort clock;private final FilePort files;
 public DemoProductAdapter(JdbcTemplate db,TimePort clock,FilePort files){this.db=db;this.clock=clock;this.files=files;}
 public Map<String,Object> lock(String id){var r=db.queryForList("SELECT * FROM products WHERE id=? FOR UPDATE",id);if(r.isEmpty())throw new BusinessException("NOT_FOUND","商品不存在",404);return r.getFirst();}
 public Map<String,Object> get(String id,Actor actor){var rows=db.queryForList("SELECT p.*,u.display_name seller_name FROM products p JOIN users u ON u.id=p.seller_id WHERE p.id=?",id);if(rows.isEmpty())throw new BusinessException("NOT_FOUND","商品不存在",404);var r=rows.getFirst();allowed("APPROVED".equals(s(r,"audit_status"))||actor!=null&&(actor.id().equals(s(r,"seller_id"))||actor.is("ADMIN")));var v=view(r);v.put("images",strings(r.get("images")));return v;}
 public void updateSale(String id,String state){db.update("UPDATE products SET sale_status=?,version=version+1 WHERE id=?",state,id);}
 private void validate(Map<String,Object> in){text(in,"title",1,120);text(in,"description",1,5000);text(in,"category",1,40);require(n(in,"price")>0&&n(in,"price")<=100_000_000,"INVALID_PRICE","商品价格需大于0且不超过100万元");require(strings(in.get("images")).size()>=1&&strings(in.get("images")).size()<=6,"INVALID_IMAGES","请上传1至6张商品照片");}
 public Map<String,Object> create(Actor a,Map<String,Object> in){allowed(a.is("USER"));validate(in);String id=id();db.update("INSERT INTO products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at) VALUES(?,?,?,?,?,?,?,'PENDING','AVAILABLE',?)",id,a.id(),text(in,"title",1,120),text(in,"description",1,5000),text(in,"category",1,40),n(in,"price"),json(strings(in.get("images"))),clock.now());files.link(a,strings(in.get("images")),"PRODUCT","PRODUCT",id);return get(id,a);}
 public Map<String,Object> update(Actor a,String id,Map<String,Object> in){var r=lock(id);allowed(a.id().equals(s(r,"seller_id")));require(n(in,"version")==n(r,"version"),"VERSION_CONFLICT","商品已更新，请刷新");require(!Set.of("LOCKED","SOLD").contains(s(r,"sale_status")),"PRODUCT_BUSY","商品已被占用或售出");
  if(in.containsKey("saleStatus")){String state=s(in,"saleStatus");require(Set.of("AVAILABLE","OFF_SHELF").contains(state),"INVALID_STATE","商品状态不正确");updateSale(id,state);}else{validate(in);db.update("UPDATE products SET title=?,description=?,category=?,price=?,images=?,audit_status='PENDING',version=version+1 WHERE id=?",text(in,"title",1,120),text(in,"description",1,5000),text(in,"category",1,40),n(in,"price"),json(strings(in.get("images"))),id);files.link(a,strings(in.get("images")),"PRODUCT","PRODUCT",id);}return get(id,a);
 }
 public void moderate(Actor a,String id,String decision,String reason,long version){allowed(a.is("ADMIN"));var r=lock(id);require(version==n(r,"version"),"VERSION_CONFLICT","商品版本已变化");require("PENDING".equals(s(r,"audit_status")),"INVALID_STATE","商品不在待审核状态");require(Set.of("APPROVED","REJECTED").contains(decision),"INVALID_DECISION","审核结论不正确");db.update("UPDATE products SET audit_status=?,version=version+1 WHERE id=?",decision,id);clock.emit(s(r,"seller_id"),id,"PRODUCT_REVIEW","商品审核结果："+(decision.equals("APPROVED")?"通过":"驳回"));}
 public List<Map<String,Object>> mine(Actor a){return db.queryForList("SELECT * FROM products WHERE seller_id=? ORDER BY created_at DESC",a.id()).stream().map(r->{var v=view(r);v.put("images",strings(r.get("images")));return v;}).toList();}
}
