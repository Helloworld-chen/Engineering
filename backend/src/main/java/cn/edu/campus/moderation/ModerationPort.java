package cn.edu.campus.moderation;
import cn.edu.campus.shared.Actor;
import java.util.*;
public interface ModerationPort {
 List<Map<String,Object>> pending(Actor actor);
 Map<String,Object> review(Actor actor,String id,String decision,String reason,long version);
 Map<String,Object> report(Actor actor,String productId,String reason);
 List<Map<String,Object>> reports(Actor actor);
 Map<String,Object> resolve(Actor actor,String id,String result);
}
