package cn.edu.campus.notification;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnExpression("${market.demo:true} && '${market.providers.notification:demo}' == 'demo'")
public class DemoController {
  private final Sessions sessions;
  private final JdbcTemplate db;
  private final DeadlineRunner runner;
  private final TimePort time;
  private final Idempotency idem;
  private final MutationGate gate;
  private final DemoSeed seed;

  public DemoController(
      Sessions s,
      JdbcTemplate db,
      DeadlineRunner r,
      TimePort t,
      Idempotency i,
      MutationGate gate,
      DemoSeed seed) {
    sessions = s;
    this.db = db;
    runner = r;
    time = t;
    idem = i;
    this.gate = gate;
    this.seed = seed;
  }

  @PostMapping("/advance")
  Map<String, Object> advance(
      @RequestBody Map<String, Object> in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.role(r, "ADMIN");
    long millis = n(in, "millis");
    BusinessException.require(
        millis > 0 && millis <= 365L * 86400000, "INVALID_TIME", "推进时间必须大于0且不超过365天");
    return gate.exclusive(
        () -> {
          idem.execute(
              a,
              "demo:advance",
              key,
              in,
              () -> {
                db.update(
                    "UPDATE clock_state SET offset_millis=offset_millis+?,frozen_at=IF(frozen_at IS"
                        + " NULL,NULL,frozen_at+?) WHERE id=1",
                    millis,
                    millis);
                return map("success", true);
              });
          runner.recover();
          return map("serverTime", instant(time.now()), "ready", runner.ready());
        });
  }

  @PostMapping("/reset")
  Map<String, Object> reset(
      @RequestBody Map<String, Object> in,
      @RequestHeader("Idempotency-Key") String key,
      HttpServletRequest r) {
    var a = sessions.role(r, "ADMIN");
    if (!"RESET".equals(s(in, "confirmation")))
      throw new BusinessException("RESET_CONFIRMATION", "请明确确认重置演示数据", 400);
    return gate.exclusive(
        () -> {
          var result =
              idem.execute(
                  a,
                  "demo:reset",
                  key,
                  in,
                  () -> {
                    // Keep lifecycle idempotency results: a lost reset response must never erase
                    // subsequent work.
                    db.update("DELETE FROM request_results WHERE operation NOT LIKE 'demo:%'");
                    for (String table :
                        List.of(
                            "notifications",
                            "outbox",
                            "deadlines",
                            "applied_decisions",
                            "case_decisions",
                            "cases",
                            "shipments",
                            "money_entries",
                            "money_operations",
                            "escrows",
                            "product_holds",
                            "order_reviews",
                            "orders",
                            "user_profiles",
                            "audit_log",
                            "reports",
                            "moderation_reviews",
                            "products")) db.update("DELETE FROM " + table);
                    db.update("UPDATE files SET linked_type=NULL,linked_id=NULL");
                    db.update(
                        "UPDATE accounts SET balance=CASE WHEN user_id IN"
                            + " ('buyer','other','admin','support') THEN 20000 WHEN"
                            + " user_id='seller' THEN 5000 ELSE 0 END");
                    db.update("UPDATE clock_state SET offset_millis=0,frozen_at=NULL WHERE id=1");
                    seed.seed();
                    return map("success", true);
                  });
          runner.recover();
          return result;
        });
  }
}
