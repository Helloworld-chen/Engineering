package cn.edu.campus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {
 @LocalServerPort int port;
 @Test void anonymousCanReadCatalogAndObtainCsrfToken() throws Exception {
  var client=HttpClient.newHttpClient();
  var r=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
  assertThat(r.statusCode()).isEqualTo(200); assertThat(r.body()).contains("csrfToken");
 }
}
