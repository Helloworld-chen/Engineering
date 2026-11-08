package cn.edu.campus;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "market.scheduling=false")
class ApiSmokeTest {
  @LocalServerPort int port;

  @Test
  void anonymousCanReadCatalogAndObtainCsrfToken() throws Exception {
    var client = HttpClient.newHttpClient();
    var r =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/session"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("csrfToken");
  }
}
