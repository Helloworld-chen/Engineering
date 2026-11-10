package cn.edu.campus.identity;

import static cn.edu.campus.shared.Rows.id;

import cn.edu.campus.shared.Actor;
import cn.edu.campus.shared.BusinessException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "market.providers.identity", havingValue = "real")
public class RealIdentityAdapter implements IdentityPort {
  private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_]{4,20}");
  private static final Pattern LOGIN_USERNAME = Pattern.compile("[a-zA-Z0-9_]{3,32}");
  private static final RowMapper<Actor> ACTOR =
      (row, index) ->
          new Actor(
              row.getString("id"),
              row.getString("username"),
              row.getString("display_name"),
              row.getString("role"));

  private final JdbcTemplate db;
  private final PasswordEncoder passwords;

  public RealIdentityAdapter(JdbcTemplate db, PasswordEncoder passwords) {
    this.db = db;
    this.passwords = passwords;
  }

  @Override
  public Actor authenticate(String username, String password) {
    if (username == null || !LOGIN_USERNAME.matcher(username).matches()
        || password == null || !validLength(password, 8, 64)) {
      throw loginFailed();
    }
    record Credentials(Actor actor, String hash) {}
    var account =
        db.query(
                "SELECT id,username,display_name,role,password_hash FROM users WHERE LOWER(username)=?",
                (row, index) -> new Credentials(ACTOR.mapRow(row, index), row.getString("password_hash")),
                username.toLowerCase(Locale.ROOT))
            .stream()
            .findFirst()
            .orElseThrow(RealIdentityAdapter::loginFailed);
    try {
      if (passwords.matches(password, account.hash())) return account.actor();
    } catch (IllegalArgumentException ignored) {
      // Legacy BCrypt hashes can reject passwords beyond BCrypt's byte limit.
    }
    throw loginFailed();
  }

  @Override
  @Transactional
  public Actor register(String username, String password, String displayName) {
    if (username == null || !USERNAME.matcher(username).matches()) {
      throw new BusinessException("INVALID_INPUT", "用户名需为4至20位字母、数字或下划线", 400);
    }
    if (password == null || password.isBlank() || !validLength(password, 8, 64)) {
      throw new BusinessException("INVALID_INPUT", "密码需为8至64个字符", 400);
    }
    requireDisplayName(displayName);
    String canonical = username.toLowerCase(Locale.ROOT);
    // New usernames are normalized before insertion; the unique key also covers concurrent requests.
    if (!db.queryForList("SELECT id FROM users WHERE LOWER(username)=?", String.class, canonical)
        .isEmpty()) {
      throw usernameTaken();
    }
    String userId = id();
    try {
      db.update(
          "INSERT INTO users(id,username,password_hash,display_name,role) VALUES(?,?,?,?,?)",
          userId, canonical, passwords.encode(password), displayName, "USER");
    } catch (DuplicateKeyException e) {
      throw usernameTaken();
    }
    db.update("INSERT INTO accounts(user_id,balance) VALUES(?,0)", userId);
    return find(userId);
  }

  @Override
  public Actor find(String userId) {
    return db.query("SELECT id,username,display_name,role FROM users WHERE id=?", ACTOR, userId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new BusinessException("UNAUTHENTICATED", "请先登录", 401));
  }

  @Override
  public Actor updateProfile(Actor actor, String displayName) {
    if (displayName == null || displayName.isBlank() || !validLength(displayName, 1, 80)) {
      throw new BusinessException("INVALID_INPUT", "昵称需为1至80个字符", 400);
    }
    db.update("UPDATE users SET display_name=? WHERE id=?", displayName, actor.id());
    return find(actor.id());
  }

  private static void requireDisplayName(String name) {
    if (name == null || name.isBlank() || !validLength(name, 1, 20)) {
      throw new BusinessException("INVALID_INPUT", "昵称需为1至20个字符", 400);
    }
  }

  private static boolean validLength(String text, int min, int max) {
    int length = text.codePointCount(0, text.length());
    return length >= min && length <= max;
  }

  private static BusinessException loginFailed() {
    return new BusinessException("LOGIN_FAILED", "用户名或密码不正确", 401);
  }

  private static BusinessException usernameTaken() {
    return new BusinessException("CONFLICT", "用户名已存在", 409);
  }
}
