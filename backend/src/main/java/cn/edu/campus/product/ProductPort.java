package cn.edu.campus.product;

import cn.edu.campus.shared.Actor;
import java.util.*;

/** M04. lock/updateSale must join caller's transaction; never start REQUIRES_NEW. */
public interface ProductPort {
  Map<String, Object> lock(String id);

  Map<String, Object> get(String id, Actor actor);

  void updateSale(String id, String state);

  Map<String, Object> create(Actor actor, Map<String, Object> input);

  Map<String, Object> update(Actor actor, String id, Map<String, Object> input);

  void moderate(Actor actor, String id, String decision, String reason, long version);

  /** Takes down an approved listing that is still available. The caller records the reason. */
  void takedown(Actor admin, String id, String reason, long version);

  List<Map<String, Object>> mine(Actor actor);
}
