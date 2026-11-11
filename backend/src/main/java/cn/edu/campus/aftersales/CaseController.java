package cn.edu.campus.aftersales;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cases")
public class CaseController {
  private final AftersalesPort cases;
  private final Sessions sessions;
  private final Idempotency idem;

  public CaseController(AftersalesPort c, Sessions s, Idempotency i) {
    cases = c;
    sessions = s;
    idem = i;
  }

  record Open(String orderId, String kind, String reason) {}

  record Action(String action, String reason) {}

  record Message(String content) {}

  @GetMapping("/{id}/messages")
  Map<String, Object> messages(@PathVariable String id, HttpServletRequest r) {
    return map("items", cases.messages(sessions.require(r), id));
  }

  @PostMapping("/{id}/messages")
  Map<String, Object> message(
      @PathVariable String id,
      @RequestBody Message in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.require(r);
    BusinessException.allowed(cases.list(a).stream().anyMatch(c -> id.equals(s(c, "id"))));
    return idem.execute(
        a,
        "case:message:" + id,
        key,
        in,
        () -> cases.message(a, id, text(map("content", in.content()), "content", 1, 500)));
  }

  @GetMapping
  Map<String, Object> list(HttpServletRequest r) {
    return map("items", cases.list(sessions.require(r)));
  }

  @PostMapping
  Map<String, Object> open(
      @RequestBody Open in, @RequestHeader("Idempotency-Key") String key, HttpServletRequest r) {
    var a = sessions.require(r);
    return idem.execute(
        a, "case:open", key, in, () -> cases.open(a, in.orderId(), in.kind(), in.reason()));
  }

  @PostMapping("/{id}/actions")
  Map<String, Object> act(
      @PathVariable String id,
      @RequestBody Action in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.require(r);
    BusinessException.allowed(cases.list(a).stream().anyMatch(c -> id.equals(s(c, "id"))));
    return idem.execute(
        a,
        "case:" + id,
        key,
        in,
        () -> cases.act(a, id, text(map("action", in.action()), "action", 1, 32), in.reason()));
  }
}
