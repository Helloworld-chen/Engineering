package cn.edu.campus.identity;

import cn.edu.campus.shared.Idempotency;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class UserProfileController {
  private final Sessions sessions;
  private final UserProfileService profiles;
  private final Idempotency idem;

  UserProfileController(Sessions sessions, UserProfileService profiles, Idempotency idem) {
    this.sessions = sessions;
    this.profiles = profiles;
    this.idem = idem;
  }

  @GetMapping("/profile")
  Map<String, Object> own(HttpServletRequest request) {
    return profiles.own(sessions.require(request));
  }

  @PatchMapping("/profile")
  Map<String, Object> patch(@RequestBody Map<String, Object> input,
      @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
    var actor = sessions.require(request);
    return idem.execute(actor, "profile", key, input, () -> profiles.update(actor, input));
  }

  @GetMapping("/users/{id}/profile")
  Map<String, Object> view(@PathVariable String id, @RequestParam(required = false) String contextType,
      @RequestParam(required = false) String contextId, HttpServletRequest request) {
    return profiles.view(sessions.require(request), id, contextType, contextId);
  }

  @GetMapping("/users/{id}/products")
  Map<String, Object> products(@PathVariable String id, @RequestParam(required = false) String contextType,
      @RequestParam(required = false) String contextId, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "ALL") String status, HttpServletRequest request) {
    return profiles.products(sessions.require(request), id, contextType, contextId, page, status);
  }

  @GetMapping("/users/{id}/reviews")
  Map<String, Object> reviews(@PathVariable String id, @RequestParam(required = false) String contextType,
      @RequestParam(required = false) String contextId, @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "ALL") String role, HttpServletRequest request) {
    return profiles.reviews(sessions.require(request), id, contextType, contextId, page, role);
  }
}
