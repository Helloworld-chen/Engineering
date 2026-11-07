package cn.edu.campus.trade;

import cn.edu.campus.shared.Actor;
import java.util.*;

/** M02 business operations. HTTP adapters wrap modifications in Idempotency.execute. */
public interface TradePort {
  Map<String, Object> place(Actor actor, String productId);

  Map<String, Object> cancel(Actor actor, String orderId);

  Map<String, Object> pay(Actor actor, String orderId);

  Map<String, Object> ship(
      Actor actor,
      String orderId,
      String direction,
      String tracking,
      List<String> goods,
      List<String> packaging);

  Map<String, Object> confirm(Actor actor, String orderId);

  Map<String, Object> query(Actor actor, String orderId);

  List<Map<String, Object>> list(Actor actor, String side);
}
