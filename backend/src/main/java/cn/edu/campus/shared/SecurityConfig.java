package cn.edu.campus.shared;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
@Configuration
public class SecurityConfig {
 @Bean PasswordEncoder passwords(){return new BCryptPasswordEncoder();}
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  var repository=new HttpSessionCsrfTokenRepository();repository.setHeaderName("X-CSRF-TOKEN");
  http.csrf(c->c.csrfTokenRepository(repository).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
   .authorizeHttpRequests(a->a.anyRequest().permitAll())
   .exceptionHandling(e->e.accessDeniedHandler((req,res,ex)->{res.setStatus(403);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"CSRF_REJECTED\",\"message\":\"会话校验失败，请刷新后重试\"}");}))
   .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable());return http.build();
 }
}
