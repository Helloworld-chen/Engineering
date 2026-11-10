package cn.edu.campus.shared;

import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService unusedBasicAuth() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(username);
    };
  }

  @Bean
  PasswordEncoder passwords() {
    var pbkdf2 = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    // Base64 keeps the salted hash and its algorithm identifier within the existing column.
    pbkdf2.setEncodeHashAsBase64(true);
    var bcrypt = new BCryptPasswordEncoder();
    var encoder =
        new DelegatingPasswordEncoder("pbkdf2-v5_8", Map.of("pbkdf2-v5_8", pbkdf2, "bcrypt", bcrypt));
    encoder.setDefaultPasswordEncoderForMatches(bcrypt);
    return encoder;
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    var repository = new HttpSessionCsrfTokenRepository();
    repository.setHeaderName("X-CSRF-TOKEN");
    http.csrf(
            c ->
                c.csrfTokenRepository(repository)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/json;charset=UTF-8");
                      res.getWriter()
                          .write("{\"code\":\"CSRF_REJECTED\",\"message\":\"会话校验失败，请刷新后重试\"}");
                    }))
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable());
    return http.build();
  }
}
