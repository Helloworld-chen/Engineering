package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.edu.campus.aftersales.AftersalesPort;
import cn.edu.campus.aftersales.RealAftersalesAdapter;
import cn.edu.campus.notification.NotificationPort;
import cn.edu.campus.notification.RealDeadlineRunner;
import cn.edu.campus.notification.RealNotificationAdapter;
import cn.edu.campus.notification.RealTimeAdapter;
import cn.edu.campus.notification.TaskRuntimePort;
import cn.edu.campus.notification.TimePort;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * M07/M08 正式实现（market.providers.*=real）的集成测试。
 *
 * <p>覆盖：real 提供者装配、退款→拒绝→申诉→客服裁决完整链路、订单状态联动、
 * 站内通知生成，以及 M08 恢复门控就绪。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "market.providers.aftersales=real",
      "market.providers.notification=real",
      "market.scheduling=false",
      "market.upload-dir=${java.io.tmpdir}/campus-market-real-test-uploads"
    })
class RealProviderWorkflowTest {
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired AftersalesPort cases;
  @Autowired NotificationPort notifications;
  @Autowired TimePort time;
  @Autowired TaskRuntimePort runtime;
  @Autowired PlatformTransactionManager txManager;

  final JsonMapper json = JsonMapper.builder().build();

  @BeforeEach
  void clean() {
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
            "orders",
            "request_results",
            "audit_log",
            "files")) db.update("DELETE FROM " + table);
    db.update("UPDATE clock_state SET frozen_at=?, offset_millis=0 WHERE id=1", 1794045600000L);
  }

  @Test
  void realProvidersAreWired() {
    assertThat(cases).isInstanceOf(RealAftersalesAdapter.class);
    assertThat(notifications).isInstanceOf(RealNotificationAdapter.class);
    assertThat(time).isInstanceOf(RealTimeAdapter.class);
    assertThat(runtime).isInstanceOf(RealDeadlineRunner.class);
    assertThat(runtime.ready()).isTrue();
  }

  @Test
  void refundRejectAppealAndSupportRefundCompleteTheCase() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    var support = new Browser("support");

    String orderId = paidOrder(buyer);
    var opened = buyer.post("/cases", Map.of("orderId", orderId, "kind", "REFUND", "reason", "买错了"));
    String caseId = (String) opened.get("id");
    assertThat(opened.get("status")).isEqualTo("SELLER_REVIEW");

    var rejected =
        seller.post("/cases/" + caseId + "/actions", Map.of("action", "REJECT", "reason", "已备货"));
    assertThat(rejected.get("status")).isEqualTo("BUYER_APPEAL");

    var appealed =
        buyer.post("/cases/" + caseId + "/actions", Map.of("action", "APPEAL", "reason", "未发货"));
    assertThat(appealed.get("status")).isEqualTo("SUPPORT_REVIEW");

    var refunded =
        support.post(
            "/cases/" + caseId + "/actions", Map.of("action", "REFUND", "reason", "核实未发货"));
    assertThat(refunded.get("status")).isEqualTo("REFUNDED");
    assertThat(
            db.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId))
        .isEqualTo("CLOSED");

    awaitNotification("buyer");
    assertThat(notificationCount("buyer")).isGreaterThanOrEqualTo(1);
  }

  @Test
  void refundWindowIsFortyEightHours() throws Exception {
    var buyer = new Browser("buyer");
    String orderId = paidOrder(buyer);
    var opened =
        buyer.post("/cases", Map.of("orderId", orderId, "kind", "REFUND", "reason", "审核期测试"));
    String caseId = (String) opened.get("id");
    long due = db.queryForObject("SELECT deadline FROM cases WHERE id=?", Long.class, caseId);
    long base = db.queryForObject("SELECT frozen_at FROM clock_state WHERE id=1", Long.class);
    var tx = new TransactionTemplate(txManager);

    // 47 小时：仍在 48 小时审核期内，不转客服
    db.update("UPDATE clock_state SET frozen_at=? WHERE id=1", base + 47 * 3_600_000L);
    tx.executeWithoutResult(status -> cases.deadline(caseId, "REFUND_REVIEW", 0, due));
    assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, caseId))
        .isEqualTo("SELLER_REVIEW");

    // 49 小时：超过 48 小时，转客服处理
    db.update("UPDATE clock_state SET frozen_at=? WHERE id=1", base + 49 * 3_600_000L);
    tx.executeWithoutResult(status -> cases.deadline(caseId, "REFUND_REVIEW", 0, due));
    assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, caseId))
        .isEqualTo("SUPPORT_REVIEW");
  }

  private String paidOrder(Browser buyer) throws Exception {
    String productId = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO products(id,seller_id,title,description,category,price,images,audit_status,"
            + "sale_status,created_at)"
            + " VALUES(?,'seller','测试商品','测试说明','数码',10000,'[]','APPROVED','AVAILABLE',0)",
        productId);
    db.update("UPDATE accounts SET balance=20000 WHERE user_id='buyer'");
    String orderId = (String) buyer.post("/orders", Map.of("productId", productId)).get("id");
    buyer.post("/orders/" + orderId + "/payment", Map.of());
    return orderId;
  }

  private void awaitNotification(String user) throws InterruptedException {
    for (int i = 0; i < 20; i++) {
      if (notificationCount(user) > 0) return;
      Thread.sleep(100);
    }
  }

  private int notificationCount(String user) {
    return db.queryForObject(
        "SELECT COUNT(*) FROM notifications WHERE recipient_id=?", Integer.class, user);
  }

  /** 带会话与 CSRF 的最小浏览器客户端。 */
  private class Browser {
    final HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String csrf;

    Browser(String username) throws Exception {
      csrf = (String) call("GET", "/session", null, null, 200).get("csrfToken");
      call(
          "POST",
          "/auth/login",
          Map.of("username", username, "password", "Campus123!"),
          UUID.randomUUID().toString(),
          200);
      csrf = (String) call("GET", "/session", null, null, 200).get("csrfToken");
    }

    Map<String, Object> post(String path, Object body) throws Exception {
      return call("POST", path, body, UUID.randomUUID().toString(), 200);
    }

    Map<String, Object> call(String method, String path, Object body, String key, int expected)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
              .timeout(java.time.Duration.ofSeconds(15))
              .header("Content-Type", "application/json");
      if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
      if (key != null) builder.header("Idempotency-Key", key);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode())
          .withFailMessage("%s %s: %s", method, path, response.body())
          .isEqualTo(expected);
      return json.readValue(response.body(), Map.class);
    }
  }
}
