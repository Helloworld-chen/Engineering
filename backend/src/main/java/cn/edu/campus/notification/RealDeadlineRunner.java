package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.aftersales.AftersalesPort;
import cn.edu.campus.shared.MutationGate;
import cn.edu.campus.trade.TradeTaskPort;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M08 到期任务的正式实现。
 *
 * <p>启动时先补办停机期间到期的任务，完成后才开放业务写入（恢复门控）；之后按固定间隔
 * 扫描 {@code deadlines}，按任务类型调用 M02 或 M07 的期限处理方法。每条任务单独事务，
 * 并在处理时复核业务状态与代次，重复扫描不会重复执行。
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "market.providers.notification", havingValue = "real")
public class RealDeadlineRunner implements ApplicationRunner, TaskRuntimePort {
  private static final Logger log = LoggerFactory.getLogger(RealDeadlineRunner.class);

  private static final Set<String> TRADE_KINDS =
      Set.of("PAYMENT", "DELIVERY", "RETURN_DELIVERY", "RECEIPT");
  private static final Set<String> CASE_KINDS =
      Set.of("REFUND_REVIEW", "RETURN_INSPECTION", "APPEAL");

  private final JdbcTemplate db;
  private final TimePort time;
  private final TradeTaskPort trade;
  private final AftersalesPort cases;
  private final TransactionTemplate tx;
  private final MutationGate gate;
  private final boolean scheduling;

  private volatile boolean ready;

  public RealDeadlineRunner(
      JdbcTemplate db,
      TimePort time,
      TradeTaskPort trade,
      AftersalesPort cases,
      PlatformTransactionManager manager,
      @Value("${market.scheduling:true}") boolean scheduling,
      MutationGate gate) {
    this.db = db;
    this.time = time;
    this.trade = trade;
    this.cases = cases;
    this.tx = new TransactionTemplate(manager);
    this.scheduling = scheduling;
    this.gate = gate;
  }

  @Override
  public boolean ready() {
    return ready;
  }

  @Override
  public void run(ApplicationArguments args) {
    recover();
  }

  /** 补办停机期间到期的任务；失败时保持门控关闭，等待下一次重试。 */
  public void recover() {
    gate.enter();
    try {
      ready = false;
      drain();
      ready = true;
    } catch (RuntimeException e) {
      log.error("到期任务恢复失败，业务写入保持关闭", e);
    } finally {
      gate.leave();
    }
  }

  @Scheduled(fixedDelay = 250)
  public void tick() {
    if (!scheduling) return;
    if (!ready) {
      recover();
      return;
    }
    try {
      drain();
    } catch (RuntimeException e) {
      ready = false;
      log.error("到期任务处理失败，暂停分发", e);
    }
  }

  public void drain() {
    gate.enter();
    try {
      drainLocked();
    } finally {
      gate.leave();
    }
  }

  private synchronized void drainLocked() {
    for (; ; ) {
      var due =
          db.queryForList(
              "SELECT * FROM deadlines WHERE status='PENDING' AND due_at<=? ORDER BY due_at,id"
                  + " LIMIT 1",
              time.now());
      if (due.isEmpty()) return;

      var task = due.getFirst();
      tx.executeWithoutResult(
          status -> {
            // 不在持有任务行锁时等待商品/订单/售后锁，避免死锁。
            String kind = s(task, "kind");
            if (TRADE_KINDS.contains(kind)) {
              trade.deadline(
                  s(task, "aggregate_id"), kind, n(task, "generation"), n(task, "due_at"));
            } else if (CASE_KINDS.contains(kind)) {
              cases.deadline(
                  s(task, "aggregate_id"), kind, n(task, "generation"), n(task, "due_at"));
            } else {
              throw new IllegalArgumentException("Unsupported task kind: " + kind);
            }
            db.update("UPDATE deadlines SET status='DONE' WHERE id=?", task.get("id"));
          });
    }
  }
}
