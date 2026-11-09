package cn.edu.campus.notification;

import cn.edu.campus.shared.Actor;
import java.util.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface NotificationPort {
  List<Map<String, Object>> list(Actor actor);

  Map<String, Object> snapshot(Actor actor);

  void read(Actor actor, long id);

  void readAll(Actor actor, long throughId);

  SseEmitter subscribe(Actor actor, String sessionId);

  void closeSession(String sessionId);
}
