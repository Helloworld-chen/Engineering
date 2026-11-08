package cn.edu.campus.aftersales;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M07 售后模块的正式持久化实现。
 *
 * <p>负责售后单与处理决定的读写。所有方法都在调用方事务内执行：调用顺序为
 * 先由 {@code TransactionCoordinator} 锁定商品/订单，再通过本类锁定并读取售后单。
 */
@Repository
@ConditionalOnProperty(name = "market.providers.aftersales", havingValue = "real")
public class RealCaseRepository implements CaseRepositoryPort {
  private final JdbcTemplate db;

  public RealCaseRepository(JdbcTemplate db) {
    this.db = db;
  }

  /** 锁定某订单下的全部售后单，供状态流转与期限处理使用。 */
  @Override
  public List<Map<String, Object>> lockForOrder(String orderId) {
    return db.queryForList("SELECT * FROM cases WHERE order_id=? ORDER BY id FOR UPDATE", orderId);
  }

  /** 读取某订单下的全部售后单（不锁定），用于展示。 */
  @Override
  public List<Map<String, Object>> forOrder(String orderId) {
    return db.queryForList("SELECT * FROM cases WHERE order_id=? ORDER BY created_at", orderId);
  }

  /** 保存售后单状态、决定与期限信息，并递增版本号。 */
  @Override
  public void save(Map<String, Object> row) {
    db.update(
        "UPDATE cases SET status=?,decision=?,decision_reason=?,deadline=?,generation=?,"
            + "version=version+1 WHERE id=?",
        row.get("status"),
        row.get("decision"),
        row.get("decision_reason"),
        row.get("deadline"),
        row.get("generation"),
        row.get("id"));
  }

  /** 读取单条处理决定。 */
  @Override
  public Map<String, Object> decision(String decisionId) {
    var rows = db.queryForList("SELECT * FROM case_decisions WHERE id=?", decisionId);
    return rows.isEmpty() ? null : rows.getFirst();
  }
}
