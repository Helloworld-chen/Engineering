package cn.edu.campus.shared;

import static cn.edu.campus.shared.Rows.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class Idempotency {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public Idempotency(JdbcTemplate db, PlatformTransactionManager manager) {
    this.db = db;
    this.tx = new TransactionTemplate(manager);
  }

  public Map<String, Object> execute(
      Actor actor,
      String operation,
      String key,
      Object input,
      Supplier<Map<String, Object>> action) {
    if (key == null || !key.matches("[A-Za-z0-9:_-]{8,100}"))
      throw new BusinessException("REQUEST_KEY_REQUIRED", "请提供有效的请求标识", 400);
    final String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(json(input).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return tx.execute(
        status -> {
          db.update(
              "INSERT IGNORE INTO request_results(actor_id,operation,request_key,parameter_hash)"
                  + " VALUES(?,?,?,?)",
              actor.id(),
              operation,
              key,
              hash);
          var row =
              db.queryForMap(
                  "SELECT * FROM request_results WHERE actor_id=? AND operation=? AND request_key=?"
                      + " FOR UPDATE",
                  actor.id(),
                  operation,
                  key);
          BusinessException.require(
              hash.equals(s(row, "parameter_hash")), "REQUEST_KEY_CONFLICT", "同一请求标识不能用于不同内容");
          if (row.get("result_json") != null) return object(s(row, "result_json"));
          var result = action.get();
          db.update(
              "UPDATE request_results SET result_json=? WHERE actor_id=? AND operation=? AND"
                  + " request_key=?",
              json(result),
              actor.id(),
              operation,
              key);
          db.update(
              "INSERT INTO audit_log(actor_id,action,aggregate_id,created_at) VALUES(?,?,?,?)",
              actor.id(),
              operation,
              result.get("id"),
              System.currentTimeMillis());
          return result;
        });
  }
}
