package cn.edu.campus.identity;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.shared.*;
import cn.edu.campus.trade.TradeMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final IdentityPort users;
  private final Sessions sessions;
  private final TradeMapper money;
  private final boolean demo;

  public AuthController(
      IdentityPort u,
      Sessions s,
      TradeMapper m,
      @Value("${market.demo}") boolean demo) {
    users = u;
    sessions = s;
    money = m;
    this.demo = demo;
  }

  @GetMapping("/session")
  Map<String, Object> session(HttpServletRequest r, CsrfToken csrf) {
    var a = sessions.optional(r);
    return map(
        "user",
        a,
        "balance",
        a == null ? null : money.balance(a.id()),
        "csrfToken",
        csrf.getToken(),
        "demo",
        demo);
  }

  private String required(String value, String field) {
    if (value == null || value.isBlank())
      throw new BusinessException("INVALID_INPUT", field + "不能为空", 400);
    return value;
  }

  record Login(String username, String password) {}

  record Registration(String username, String password, String displayName) {}

  @PostMapping("/auth/login")
  Map<String, Object> login(@RequestBody Login input, HttpServletRequest r) {
    var a = users.authenticate(required(input.username(), "用户名"), required(input.password(), "密码"));
    sessions.signIn(r, a);
    return map("user", a);
  }

  @PostMapping("/auth/register")
  Map<String, Object> register(@RequestBody Registration input, HttpServletRequest r) {
    var a =
        users.register(
            required(input.username(), "用户名"),
            required(input.password(), "密码"),
            required(input.displayName(), "昵称"));
    sessions.signIn(r, a);
    return map("user", a);
  }

  @PostMapping("/auth/logout")
  Map<String, Object> logout(HttpServletRequest r) {
    if (r.getSession(false) != null) r.getSession().invalidate();
    return map("success", true);
  }
}
