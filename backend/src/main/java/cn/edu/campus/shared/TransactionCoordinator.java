package cn.edu.campus.shared;
import cn.edu.campus.product.ProductPort;
import cn.edu.campus.trade.TradeMapper;
import cn.edu.campus.aftersales.CaseRepositoryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
@Service
public class TransactionCoordinator {
 private final ProductPort products;private final TradeMapper trade;private final CaseRepositoryPort cases;
 public TransactionCoordinator(ProductPort p,TradeMapper t,CaseRepositoryPort c){products=p;trade=t;cases=c;}
 public record Context(Map<String,Object> product,Map<String,Object> order,List<Map<String,Object>> cases){}
 @Transactional(propagation=Propagation.MANDATORY)
 public Context lock(String orderId){var reference=trade.order(orderId);if(reference==null)throw new BusinessException("NOT_FOUND","订单不存在",404);
  var p=products.lock(s(reference,"product_id"));var o=trade.lockOrder(orderId);BusinessException.require(s(o,"product_id").equals(s(p,"id")),"CONFLICT","订单关联已变化");return new Context(p,o,cases.lockForOrder(orderId));
 }
}
