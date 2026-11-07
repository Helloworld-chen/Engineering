package cn.edu.campus.identity;

import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class Sessions {
  private final IdentityPort users;

  public Sessions(IdentityPort users) {
    this.users = users;
  }

  public Actor optional(HttpServletRequest req) {
    var s = req.getSession(false);
    return s == null || s.getAttribute("userId") == null
        ? null
        : users.find(s.getAttribute("userId").toString());
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
