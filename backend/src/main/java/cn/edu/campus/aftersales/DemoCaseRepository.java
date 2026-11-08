package cn.edu.campus.aftersales;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "market.providers.aftersales", havingValue = "demo")
public class DemoCaseRepository implements CaseRepositoryPort {
  private final JdbcTemplate db;

  public DemoCaseRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> lockForOrder(String id) {
    return db.queryForList("SELECT * FROM cases WHERE order_id=? ORDER BY id FOR UPDATE", id);
  }

  public List<Map<String, Object>> forOrder(String id) {
    return db.queryForList("SELECT * FROM cases WHERE order_id=? ORDER BY created_at", id);
  }

  public void save(Map<String, Object> c) {
    db.update(
        "UPDATE cases SET"
            + " status=?,decision=?,decision_reason=?,deadline=?,generation=?,version=version+1"
            + " WHERE id=?",
        c.get("status"),
        c.get("decision"),
        c.get("decision_reason"),
        c.get("deadline"),
        c.get("generation"),
        c.get("id"));
  }

  public Map<String, Object> decision(String id) {
    var r = db.queryForList("SELECT * FROM case_decisions WHERE id=?", id);
    return r.isEmpty() ? null : r.getFirst();
  }
}
