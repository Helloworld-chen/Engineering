package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class NotificationSessionTest extends ApiTestSupport {
  @Test
  void changingAccountClosesNotificationStreamForOldSessionId() throws Exception {
    var buyer = new Browser("buyer");
    assertStreamClosedBy(
        buyer,
        () -> buyer.post("/auth/login", Map.of("username", "seller", "password", "Campus123!")));
    buyer.csrf = (String) buyer.call("GET", "/session", null, null, 200).get("csrfToken");
    assertStreamClosedBy(buyer, () -> buyer.post("/auth/logout", Map.of()));
  }

  @Test
  void registeringClosesNotificationStreamForOldSessionId() throws Exception {
    var buyer = new Browser("buyer");
    String username = "sse" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try {
      assertStreamClosedBy(
          buyer,
          () ->
              buyer.post(
                  "/auth/register",
                  Map.of("username", username, "password", "Campus123!", "displayName", "通知测试")));
    } finally {
      db.update(
          "DELETE a FROM accounts a JOIN users u ON u.id=a.user_id WHERE u.username=?", username);
      db.update("DELETE FROM users WHERE username=?", username);
    }
  }

  @Test
  void loggingOutClosesCurrentNotificationStream() throws Exception {
    var buyer = new Browser("buyer");
    assertStreamClosedBy(buyer, () -> buyer.post("/auth/logout", Map.of()));
    buyer.call("GET", "/events", null, null, 401);
  }

  private void assertStreamClosedBy(Browser browser, Mutation mutation) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/events"))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
    var response = browser.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (var worker = Executors.newSingleThreadExecutor();
        var body = response.body()) {
      assertThat(response.statusCode()).isEqualTo(200);
      var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
      var ready =
          worker.submit(
              () -> {
                String line;
                boolean readyEvent = false;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                  readyEvent |= line.equals("event:ready");
                }
                return readyEvent;
              });
      assertThat(ready.get(5, TimeUnit.SECONDS)).isTrue();

      mutation.run();
      var closed =
          worker.submit(
              () -> {
                while (reader.readLine() != null) {
                  // Consume any already queued events before checking for end of stream.
                }
                return true;
              });
      try {
        assertThat(closed.get(3, TimeUnit.SECONDS)).isTrue();
      } catch (TimeoutException e) {
        throw new AssertionError(
            "Previous notification stream stayed open after authentication changed", e);
      }
    }
  }

  @FunctionalInterface
  private interface Mutation {
    void run() throws Exception;
  }
}
