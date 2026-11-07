package cn.edu.campus.identity;
import cn.edu.campus.shared.*;
import cn.edu.campus.trade.TradeMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
@RestController @RequestMapping("/api/v1")
public class AuthController {
 private final IdentityPort users;private final Sessions sessions;private final TradeMapper money;private final Idempotency idem;private final boolean demo;
 public AuthController(IdentityPort u,Sessions s,TradeMapper m,Idempotency i,@Value("${market.demo}")boolean demo){users=u;sessions=s;money=m;idem=i;this.demo=demo;}
 @GetMapping("/session") Map<String,Object> session(HttpServletRequest r,CsrfToken csrf){var a=sessions.optional(r);return map("user",a,"balance",a==null?null:money.balance(a.id()),"csrfToken",csrf.getToken(),"demo",demo);}
 record Login(String username,String password){} record Registration(String username,String password,String displayName){}
 @PostMapping("/auth/login") Map<String,Object> login(@RequestBody Login input,HttpServletRequest r){var a=users.authenticate(Objects.requireNonNull(input.username()),Objects.requireNonNull(input.password()));r.getSession();r.changeSessionId();r.getSession().setAttribute("userId",a.id());return map("user",a);}
 @PostMapping("/auth/register") Map<String,Object> register(@RequestBody Registration input,HttpServletRequest r){var a=users.register(Objects.requireNonNull(input.username()),Objects.requireNonNull(input.password()),Objects.requireNonNull(input.displayName()));r.getSession();r.changeSessionId();r.getSession().setAttribute("userId",a.id());return map("user",a);}
 @PostMapping("/auth/logout") Map<String,Object> logout(HttpServletRequest r){if(r.getSession(false)!=null)r.getSession().invalidate();return map("success",true);}
 @PatchMapping("/profile") Map<String,Object> profile(@RequestBody Map<String,Object> input,@RequestHeader("Idempotency-Key")String key,HttpServletRequest r){var a=sessions.require(r);return idem.execute(a,"profile",key,input,()->map("user",users.updateProfile(a,text(input,"displayName",1,80))));}
}
