package cn.edu.campus.trade;
import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
@RestController @RequestMapping("/api/v1/orders")
public class TradeController {
 private final TradePort trade;private final Sessions sessions;private final Idempotency idem;
 public TradeController(TradePort t,Sessions s,Idempotency i){trade=t;sessions=s;idem=i;}
 record Place(String productId){} record Shipment(String trackingNumber,List<String> goods,List<String> packaging){}
 @PostMapping Map<String,Object> place(@RequestBody Place input,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){if(input.productId()==null||input.productId().isBlank())throw new BusinessException("INVALID_INPUT","商品编号不能为空",400);var a=sessions.require(r);BusinessException.allowed(a.is("USER"));return idem.execute(a,"order:create",key,input,()->trade.place(a,Objects.requireNonNull(input.productId())));}
 @GetMapping Map<String,Object> list(@RequestParam(defaultValue="buying")String side,HttpServletRequest r){return map("items",trade.list(sessions.require(r),side));}
 @GetMapping("/{id}") Map<String,Object> get(@PathVariable String id,HttpServletRequest r){return trade.query(sessions.require(r),id);}
 @PostMapping("/{id}/{action:payment|cancel|receipt}") Map<String,Object> change(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){if(!body.isEmpty())throw new BusinessException("INVALID_INPUT","此操作不接受金额或身份字段",400);var a=sessions.require(r);trade.query(a,id);return idem.execute(a,"order:"+id+":"+action,key,body,()->switch(action){case "payment"->trade.pay(a,id);case "cancel"->trade.cancel(a,id);default->trade.confirm(a,id);});}
 @PostMapping("/{id}/{action:shipment|return-shipment}") Map<String,Object> ship(@PathVariable String id,@PathVariable String action,@RequestBody Shipment input,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){var a=sessions.require(r);trade.query(a,id);return idem.execute(a,"order:"+id+":"+action,key,input,()->trade.ship(a,id,action.equals("shipment")?"OUTBOUND":"RETURN",input.trackingNumber(),input.goods(),input.packaging()));}
}
