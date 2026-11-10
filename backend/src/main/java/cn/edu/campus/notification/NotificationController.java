package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.Idempotency;
import cn.edu.campus.shared.BusinessException;
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
    return notifications.snapshot(sessions.require(r));
  }

  @PostMapping("/notifications/read-all")
  Map<String, Object> readAll(@RequestBody Map<String, Object> input,
      @RequestHeader("Idempotency-Key") String key, HttpServletRequest r) {
    var actor = sessions.require(r);
    long throughId;
    try {
      if (!(input.get("throughId") instanceof String text) || !text.matches("[1-9][0-9]{0,18}"))
        throw new IllegalArgumentException();
      throughId = Long.parseLong(text);
    } catch (IllegalArgumentException failure) {
      throw new BusinessException("INVALID_INPUT", "请提供有效的通知截止编号", 400);
    }
    return idem.execute(actor, "notification:read-all", key, input, () -> {
      notifications.readAll(actor, throughId);
      return map("success", true);
    });
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
