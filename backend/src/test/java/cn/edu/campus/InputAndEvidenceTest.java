package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class InputAndEvidenceTest extends WorkflowTestSupport {
  private static String key() {
    return UUID.randomUUID().toString();
  }

  private byte[] png() throws Exception {
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
    return bytes.toByteArray();
  }

  private Map<String, Object> upload(Browser browser, String purpose, byte[] bytes, int status)
      throws Exception {
    String boundary = "evidence-" + key();
    var multipart =
        HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(
                "--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                    + purpose
                    + "\r\n--"
                    + boundary
                    + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"test.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n"),
            HttpRequest.BodyPublishers.ofByteArray(bytes),
            HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .header("X-CSRF-TOKEN", browser.csrf)
            .POST(multipart)
            .build();
    var response = browser.client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode())
        .withFailMessage("Upload response: %s", response.body())
        .isEqualTo(status);
    return json.readValue(response.body(), Map.class);
  }

  private HttpResponse<byte[]> download(Browser browser, Object file, int status) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/" + file))
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
    var response = browser.client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(status);
    return response;
  }

  @Test
  void missingCsrfCannotMutateOrder() throws Exception {
    var buyer = new Browser("buyer");
    String product = product();
    buyer.csrf = null;
    assertThat(buyer.call("POST", "/orders", Map.of("productId", product), key(), 403).get("code"))
        .isEqualTo("CSRF_REJECTED");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE product_id=?", Integer.class, product))
        .isZero();
  }

  @Test
  void missingRequiredFieldsReturnClientError() throws Exception {
    var visitor = new Browser(null);
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    visitor.call("POST", "/auth/login", Map.of("username", "buyer"), key(), 400);
    visitor.call(
        "POST",
        "/auth/register",
        Map.of("username", "new-user", "password", "Campus123!"),
        key(),
        400);
    buyer.call("POST", "/orders", Map.of(), key(), 400);
    String order = paid(buyer);
    String refund =
        (String)
            buyer
                .post("/cases", Map.of("orderId", order, "kind", "REFUND", "reason", "申请退款"))
                .get("id");
    seller.call("POST", "/cases/" + refund + "/actions", Map.of("reason", "同意"), key(), 400);
    assertThat(db.queryForObject("SELECT status FROM cases WHERE id=?", String.class, refund))
        .isEqualTo("SELLER_REVIEW");
  }

  @Test
  void uploadedImagesArePrivateUntilLinkedToAccessibleOrder() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    var other = new Browser("other");
    byte[] image = png();
    Object goods = upload(seller, "GOODS", image, 200).get("id");
    Object packaging = upload(seller, "PACKAGING", image, 200).get("id");
    var own = download(seller, goods, 200);
    assertThat(own.body()).isEqualTo(image);
    assertThat(own.headers().firstValue("Content-Type")).contains("image/png");
    assertThat(own.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    download(buyer, goods, 403);
    String order = paid(buyer);
    seller.post(
        "/orders/" + order + "/shipment",
        Map.of(
            "trackingNumber", "EVID001", "goods", List.of(goods), "packaging", List.of(packaging)));
    assertThat(download(buyer, goods, 200).body()).isEqualTo(image);
    download(other, goods, 403);
  }

  @Test
  void rejectsSpoofedCorruptAndOversizedImagesWithoutPersistingFiles() throws Exception {
    var seller = new Browser("seller");
    assertThat(upload(seller, "GOODS", new byte[] {1, 2, 3}, 409).get("code"))
        .isEqualTo("INVALID_IMAGE");
    assertThat(upload(seller, "GOODS", Arrays.copyOf(png(), 16), 400).get("code"))
        .isEqualTo("INVALID_IMAGE");
    assertThat(upload(seller, "GOODS", new byte[5_000_001], 409).get("code"))
        .isEqualTo("INVALID_FILE");
    assertThat(db.queryForObject("SELECT COUNT(*) FROM files", Integer.class)).isZero();
  }

  @Test
  void evidenceMustMatchOwnerPurposeAndOriginalOrder() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String first = paid(buyer);
    String second = paid(buyer);
    String sellerGoods = file("seller", "GOODS");
    String wrongOwner = file("buyer", "PACKAGING");
    String packaging = file("seller", "PACKAGING");
    String path = "/orders/" + first + "/shipment";

    var wrongOwnerResult =
        seller.call(
            "POST",
            path,
            Map.of(
                "trackingNumber",
                "EVID001",
                "goods",
                List.of(sellerGoods),
                "packaging",
                List.of(wrongOwner)),
            key(),
            403);
    assertThat(wrongOwnerResult.get("code")).isEqualTo("FORBIDDEN");
    assertThat(
            db.queryForObject("SELECT linked_id FROM files WHERE id=?", String.class, sellerGoods))
        .isNull();
    var wrongPurposeResult =
        seller.call(
            "POST",
            path,
            Map.of(
                "trackingNumber",
                "EVID001",
                "goods",
                List.of(packaging),
                "packaging",
                List.of(sellerGoods)),
            key(),
            409);
    assertThat(wrongPurposeResult.get("code")).isEqualTo("FILE_PURPOSE");
    var valid =
        Map.of(
            "trackingNumber",
            "EVID001",
            "goods",
            List.of(sellerGoods),
            "packaging",
            List.of(packaging));
    seller.post(path, valid);
    var wrongOrderResult =
        seller.call("POST", "/orders/" + second + "/shipment", valid, key(), 409);
    assertThat(wrongOrderResult.get("code")).isEqualTo("FILE_LINKED");
    assertThat(
            db.queryForObject("SELECT linked_id FROM files WHERE id=?", String.class, sellerGoods))
        .isEqualTo(first);
    assertThat(state(second)).isEqualTo("AWAITING_SHIPMENT");
  }

  @Test
  void orderPriceAndPermissionsAreEnforcedByServer() throws Exception {
    var buyer = new Browser("buyer");
    var outsider = new Browser("other");
    String product = product();
    buyer.call("POST", "/orders", Map.of("productId", product, "amount", 1), key(), 400);
    String order = (String) buyer.post("/orders", Map.of("productId", product)).get("id");
    assertThat(db.queryForObject("SELECT amount FROM orders WHERE id=?", Long.class, order))
        .isEqualTo(10_000);
    outsider.call("GET", "/orders/" + order, null, null, 403);
    outsider.call("POST", "/orders/" + order + "/payment", Map.of(), key(), 403);
    buyer.call("POST", "/orders/" + order + "/payment", Map.of("amount", 1), key(), 400);
    buyer.post("/orders/" + order + "/payment", Map.of());
    outsider.call("POST", "/orders/" + order + "/shipment", evidence(false), key(), 403);
    assertThat(state(order)).isEqualTo("AWAITING_SHIPMENT");
    assertThat(
            db.queryForObject(
                "SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, order))
        .isZero();
  }

  @Test
  void shipmentValidatesTrackingAndBothEvidenceCategories() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String order = paid(buyer);
    String path = "/orders/" + order + "/shipment";
    assertThat(seller.call("POST", path, Map.of(), key(), 409).get("code"))
        .isEqualTo("INVALID_TRACKING");
    var goods = List.of(file("seller", "GOODS"));
    var packaging = List.of(file("seller", "PACKAGING"));
    assertThat(
            seller
                .call("POST", path, Map.of("trackingNumber", "EVID001", "goods", goods), key(), 409)
                .get("code"))
        .isEqualTo("INVALID_EVIDENCE");
    assertThat(
            seller
                .call(
                    "POST",
                    path,
                    Map.of("trackingNumber", "EVID001", "packaging", packaging),
                    key(),
                    409)
                .get("code"))
        .isEqualTo("INVALID_EVIDENCE");
    assertThat(state(order)).isEqualTo("AWAITING_SHIPMENT");
    assertThat(
            db.queryForObject("SELECT COUNT(*) FROM files WHERE linked_id=?", Integer.class, order))
        .isZero();
  }
}
