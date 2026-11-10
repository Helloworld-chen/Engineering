package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "market.providers.identity=real")
class RealIdentityHttpTest extends ApiTestSupport {
  @Test
  void registersCaseInsensitiveUsernameAndUsesExistingSessionContract() throws Exception {
    String username = "M03_" + UUID.randomUUID().toString().substring(0, 8);
    String canonical = username.toLowerCase(Locale.ROOT);
    String password = "测".repeat(64);
    var browser = new Browser(null);
    try {
      var registration =
          browser.post(
              "/auth/register",
              Map.of("username", username, "password", password, "displayName", "身份测试"));
      var user = (Map<?, ?>) registration.get("user");
      assertThat(user.get("role")).isEqualTo("USER");
      assertThat(user.containsKey("password") || user.containsKey("passwordHash")).isFalse();
      assertThat(
              db.queryForObject(
                  "SELECT password_hash FROM users WHERE username=?", String.class, canonical))
          .startsWith("{pbkdf2-v5_8}")
          .doesNotContain(password);
      var duplicate =
          new Browser(null)
              .call(
                  "POST",
                  "/auth/register",
                  Map.of("username", canonical, "password", password, "displayName", "重复"),
                  null,
                  409);
      assertThat(duplicate.get("code")).isEqualTo("CONFLICT");
      assertThat(db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, canonical))
          .isEqualTo(1);
      assertThat(browser.call("GET", "/session", null, null, 200).get("user")).isNotNull();
      browser.post("/auth/logout", Map.of());
      assertThat(browser.call("GET", "/session", null, null, 200).get("user")).isNull();

      var login = new Browser(null);
      login.call("POST", "/auth/login", Map.of("username", canonical, "password", "错误的密码"), null, 401);
      assertThat(
              login
                  .post("/auth/login", Map.of("username", username, "password", password))
                  .get("user"))
          .isNotNull();
      login.call(
          "PATCH",
          "/profile",
          Map.of("displayName", "越权修改", "role", "ADMIN"),
          UUID.randomUUID().toString(),
          400);
      assertThat(db.queryForObject("SELECT display_name FROM users WHERE username=?", String.class, canonical))
          .isEqualTo("身份测试");
    } finally {
      db.update("DELETE a FROM accounts a JOIN users u ON u.id=a.user_id WHERE u.username=?", canonical);
      db.update("DELETE FROM users WHERE username=?", canonical);
    }
  }

  @Test
  void acceptsExistingUnprefixedBcryptPassword() throws Exception {
    String username = "legacy_" + UUID.randomUUID().toString().substring(0, 8);
    String userId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO users(id,username,password_hash,display_name,role) VALUES(?,?,?,?,?)",
        userId, username, new BCryptPasswordEncoder(4).encode("Legacy123!"), "旧账号", "USER");
    try {
      var response =
          new Browser(null)
              .post("/auth/login", Map.of("username", username, "password", "Legacy123!"));
      assertThat(response.get("user")).asString().contains(username);
    } finally {
      db.update("DELETE FROM users WHERE id=?", userId);
    }
  }
}
