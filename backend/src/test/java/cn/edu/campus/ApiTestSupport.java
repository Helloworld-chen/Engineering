package cn.edu.campus;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "market.scheduling=false",
      "market.upload-dir=${java.io.tmpdir}/campus-market-test-uploads"
    })
abstract class ApiTestSupport {
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  final JsonMapper json = JsonMapper.builder().build();

  class Browser {
    HttpClient client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String csrf;

    Browser(String username) throws Exception {
      csrf = (String) call("GET", "/session", null, null, 200).get("csrfToken");
      if (username != null) {
        call(
            "POST",
            "/auth/login",
            Map.of("username", username, "password", "Campus123!"),
            UUID.randomUUID().toString(),
            200);
        csrf = (String) call("GET", "/session", null, null, 200).get("csrfToken");
      }
    }

    Map<String, Object> call(String method, String path, Object body, String key, int expected)
        throws Exception {
      var b =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
              .timeout(java.time.Duration.ofSeconds(15))
              .header("Content-Type", "application/json");
      if (csrf != null) b.header("X-CSRF-TOKEN", csrf);
      if (key != null) b.header("Idempotency-Key", key);
      b.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      var r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
      assertThat(r.statusCode())
          .withFailMessage("%s %s: %s", method, path, r.body())
          .isEqualTo(expected);
      return json.readValue(r.body(), Map.class);
    }

    Map<String, Object> post(String path, Object body) throws Exception {
      return call("POST", path, body, UUID.randomUUID().toString(), 200);
    }
  }

  @BeforeEach
  void time() {
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
            "request_results",
            "audit_log",
            "user_profiles",
            "files",
            "reports",
            "moderation_reviews")) db.update("DELETE FROM " + table);
    db.update("UPDATE clock_state SET frozen_at=?, offset_millis=0 WHERE id=1", 1794045600000L);
  }

  String product() {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO"
            + " products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at)"
            + " VALUES(?,'seller','测试商品','测试说明','数码',10000,'[]','APPROVED','AVAILABLE',0)",
        id);
    db.update("UPDATE accounts SET balance=20000 WHERE user_id='buyer'");
    db.update("UPDATE accounts SET balance=5000 WHERE user_id='seller'");
    return id;
  }

  String order(Browser b) throws Exception {
    return (String) b.post("/orders", Map.of("productId", product())).get("id");
  }
}
