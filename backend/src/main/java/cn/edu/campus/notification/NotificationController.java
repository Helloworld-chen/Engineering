package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class NotificationController {
  private final NotificationPort notifications;
  private final Sessions sessions;
  private final Idempotency idem;

  public NotificationController(NotificationPort n, Sessions s, Idempotency i) {
    notifications = n;
    sessions = s;
    idem = i;
  }

  @GetMapping("/notifications")
  Map<String, Object> list(HttpServletRequest r) {
    return map("items", notifications.list(sessions.require(r)));
  }

  @PostMapping("/notifications/{id}/read")
  Map<String, Object> read(
      @PathVariable long id, @RequestHeader("Idempotency-Key") String key, HttpServletRequest r) {
    var a = sessions.require(r);
    return idem.execute(
        a,
        "notification:" + id,
        key,
        Map.of(),
        () -> {
          notifications.read(a, id);
          return map("success", true);
        });
  }

  @GetMapping(value = "/events", produces = "text/event-stream")
  SseEmitter events(HttpServletRequest r) {
    return notifications.subscribe(sessions.require(r), r.getSession().getId());
  }
}
