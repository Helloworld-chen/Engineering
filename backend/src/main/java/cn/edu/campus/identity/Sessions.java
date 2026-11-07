package cn.edu.campus.identity;

import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Sessions {
  static final String EXPIRES_AT = "authenticationExpiresAt";
  private static final Duration LIFETIME = Duration.ofHours(2);
  private final IdentityPort users;
  private final boolean fixedExpiry;

  public Sessions(IdentityPort users, @Value("${market.providers.identity}") String provider) {
    this.users = users;
    fixedExpiry = "real".equals(provider);
  }

  public void signIn(HttpServletRequest req, Actor actor) {
    var session = req.getSession();
    req.changeSessionId();
    if (fixedExpiry) {
      session.setMaxInactiveInterval((int) LIFETIME.toSeconds());
      session.setAttribute(EXPIRES_AT, Instant.now().plus(LIFETIME));
    }
    session.setAttribute("userId", actor.id());
  }

  public Actor optional(HttpServletRequest req) {
    var session = req.getSession(false);
    if (session == null) return null;
    String userId;
    try {
      if (!(session.getAttribute("userId") instanceof String id)) return null;
      if (fixedExpiry
          && (!(session.getAttribute(EXPIRES_AT) instanceof Instant deadline)
              || !Instant.now().isBefore(deadline))) {
        session.invalidate();
        return null;
      }
      userId = id;
    } catch (IllegalStateException e) {
      // A concurrent logout can invalidate the session while this request is reading it.
      return null;
    }
    return users.find(userId);
  }

  public Actor require(HttpServletRequest req) {
    var a = optional(req);
    if (a == null) throw new BusinessException("UNAUTHENTICATED", "请先登录", 401);
    return a;
  }

  public Actor role(HttpServletRequest req, String role) {
    var a = require(req);
    BusinessException.allowed(a.is(role));
    return a;
  }
}
