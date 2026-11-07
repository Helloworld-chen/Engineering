package cn.edu.campus.identity;
import cn.edu.campus.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
@Service @ConditionalOnProperty(name="market.providers.identity",havingValue="demo")
public class DemoIdentityAdapter implements IdentityPort {
 private final JdbcTemplate db; private final PasswordEncoder encoder;
 public DemoIdentityAdapter(JdbcTemplate db,PasswordEncoder encoder){this.db=db;this.encoder=encoder;}
 private Actor actor(Map<String,Object> r){return new Actor(s(r,"id"),s(r,"username"),s(r,"display_name"),s(r,"role"));}
 public Actor find(String id){var rows=db.queryForList("SELECT * FROM users WHERE id=?",id);if(rows.isEmpty())throw new BusinessException("UNAUTHENTICATED","请先登录",401);return actor(rows.getFirst());}
 public Actor authenticate(String username,String password){var rows=db.queryForList("SELECT * FROM users WHERE username=?",username);if(rows.isEmpty()||!encoder.matches(password,s(rows.getFirst(),"password_hash")))throw new BusinessException("LOGIN_FAILED","用户名或密码不正确",401);return actor(rows.getFirst());}
 @Transactional public Actor register(String username,String password,String displayName){
  if(!username.matches("[a-zA-Z0-9_]{3,32}")||password.length()<8||password.length()>64||displayName.isBlank()||displayName.length()>80)throw new BusinessException("INVALID_INPUT","用户名需3至32位字母数字或下划线，密码需8至64位，昵称不能为空",400);
  String id=id();db.update("INSERT INTO users VALUES(?,?,?,?,?)",id,username,encoder.encode(password),displayName,"USER");db.update("INSERT INTO accounts VALUES(?,0)",id);return find(id);
 }
 public Actor updateProfile(Actor actor,String name){if(name.isBlank()||name.length()>80)throw new BusinessException("INVALID_INPUT","昵称需1至80个字符",400);db.update("UPDATE users SET display_name=? WHERE id=?",name,actor.id());return find(actor.id());}
}
