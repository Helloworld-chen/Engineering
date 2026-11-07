package cn.edu.campus.moderation;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ModerationController {
  private final ModerationPort module;
  private final Sessions sessions;
  private final Idempotency idem;

  public ModerationController(ModerationPort m, Sessions s, Idempotency i) {
    module = m;
    sessions = s;
    idem = i;
  }

  @GetMapping("/moderation/products")
  Map<String, Object> pending(HttpServletRequest r) {
    return map("items", module.pending(sessions.role(r, "ADMIN")));
  }

  @PostMapping("/moderation/products/{id}")
  Map<String, Object> review(
      @PathVariable String id,
      @RequestBody Map<String, Object> in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.role(r, "ADMIN");
    return idem.execute(
        a,
        "moderate:" + id,
        key,
        in,
        () -> module.review(a, id, s(in, "decision"), s(in, "reason"), n(in, "version")));
  }

  @PostMapping("/reports")
  Map<String, Object> report(
      @RequestBody Map<String, Object> in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.require(r);
    return idem.execute(
        a, "report:create", key, in, () -> module.report(a, s(in, "productId"), s(in, "reason")));
  }

  @GetMapping("/reports")
  Map<String, Object> reports(HttpServletRequest r) {
    return map("items", module.reports(sessions.require(r)));
  }

  @PostMapping("/reports/{id}/resolve")
  Map<String, Object> resolve(
      @PathVariable String id,
      @RequestBody Map<String, Object> in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.role(r, "ADMIN");
    return idem.execute(a, "report:" + id, key, in, () -> module.resolve(a, id, s(in, "result")));
  }
}
