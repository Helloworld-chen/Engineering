package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * M08 业务时钟的正式实现。
 *
 * <p>业务时间由 {@code clock_state} 的偏移量决定：正常模式下为系统时间加偏移，
 * 演示模式下为冻结时刻。所有期限与任务都使用该时间，避免各模块各自取系统时间。
 */
@Service
@ConditionalOnProperty(name = "market.providers.notification", havingValue = "real")
public class RealTimeAdapter implements TimePort {
  private final JdbcTemplate db;

  public RealTimeAdapter(JdbcTemplate db) {
    this.db = db;
  }

  /** 当前业务时间（毫秒）。 */
  @Override
  public long now() {
    var state = db.queryForMap("SELECT * FROM clock_state WHERE id=1");
    return state.get("frozen_at") == null
        ? System.currentTimeMillis() + n(state, "offset_millis")
        : n(state, "frozen_at");
  }

  /** 登记到期任务；同一聚合、类型与代次只保留一条。 */
  @Override
  public void schedule(String aggregateId, String kind, long generation, long dueAt) {
    db.update(
        "INSERT IGNORE INTO deadlines(aggregate_id,kind,generation,due_at) VALUES(?,?,?,?)",
        aggregateId,
        kind,
        generation,
        dueAt);
  }

  /** 写入待投递事件，由通知分发器在事务提交后处理。 */
  @Override
  public void emit(String recipient, String aggregateId, String type, String message) {
    db.update(
        "INSERT INTO outbox(recipient_id,aggregate_id,event_type,message,created_at)"
            + " VALUES(?,?,?,?,?)",
        recipient,
        aggregateId,
        type,
        message,
        now());
  }
}
