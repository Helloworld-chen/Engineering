package cn.edu.campus.product;
import cn.edu.campus.browse.BrowsePort;
import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
@RestController @RequestMapping("/api/v1")
public class ProductController {
 private final ProductPort products;private final BrowsePort browse;private final Sessions sessions;private final Idempotency idem;
 public ProductController(ProductPort p,BrowsePort b,Sessions s,Idempotency i){products=p;browse=b;sessions=s;idem=i;}
 @GetMapping("/products") Map<String,Object> list(@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String category,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="12")int pageSize){return browse.search(keyword,category,page,pageSize);}
 @GetMapping("/products/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest r){return products.get(id,sessions.optional(r));}
 @GetMapping("/my/products") Map<String,Object> mine(HttpServletRequest r){return map("items",products.mine(sessions.require(r)));}
 @PostMapping("/products") Map<String,Object> create(@RequestBody Map<String,Object> in,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){var a=sessions.require(r);return idem.execute(a,"product:create",key,in,()->products.create(a,in));}
 @PatchMapping("/products/{id}") Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> in,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){var a=sessions.require(r);return idem.execute(a,"product:"+id,key,in,()->products.update(a,id,in));}
}
