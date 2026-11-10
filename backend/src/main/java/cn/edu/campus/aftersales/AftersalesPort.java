package cn.edu.campus.aftersales;

import cn.edu.campus.shared.Actor;
import java.util.*;

public interface AftersalesPort {
  Map<String, Object> open(Actor actor, String orderId, String kind, String reason);

  Map<String, Object> act(Actor actor, String caseId, String action, String reason);

  List<Map<String, Object>> list(Actor actor);

  /** 在售后单内留言，买卖双方与客服三方可见。 */
  Map<String, Object> message(Actor actor, String caseId, String content);

  /** 查询售后单的留言记录，按时间升序。 */
  List<Map<String, Object>> messages(Actor actor, String caseId);

  void deadline(String caseId, String kind, long generation, long dueAt);
}
