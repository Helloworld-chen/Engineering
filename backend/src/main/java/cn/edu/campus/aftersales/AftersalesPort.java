package cn.edu.campus.aftersales;

import cn.edu.campus.shared.Actor;
import java.util.*;

public interface AftersalesPort {
  Map<String, Object> open(Actor actor, String orderId, String kind, String reason);

  Map<String, Object> act(Actor actor, String caseId, String action, String reason);

  List<Map<String, Object>> list(Actor actor);

  void deadline(String caseId, String kind, long generation, long dueAt);
}
