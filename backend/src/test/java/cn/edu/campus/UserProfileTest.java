package cn.edu.campus;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class UserProfileTest extends WorkflowTestSupport {
  String profile(String id, String type, String context) {
    return "/users/" + id + "/profile?contextType=" + type + "&contextId=" + context;
  }

  Map<String, Object> patch(Browser browser, Map<String, Object> input, int status) throws Exception {
    return browser.call("PATCH", "/profile", input, UUID.randomUUID().toString(), status);
  }

  @Test void selfProfileSupportsPartialUpdatesAndLegacyName() throws Exception {
    var seller = new Browser("seller");
    String name = (String) ((Map<?, ?>) seller.call("GET", "/session", null, null, 200).get("user")).get("displayName");
    try {
      assertThat(seller.call("GET", "/profile", null, null, 200))
          .containsEntry("avatarId", null).containsEntry("school", "").containsEntry("bio", "");
      var result = patch(seller, Map.of("displayName", "  新昵称  ", "school", "学校", "campus", "校区", "bio", "自我介绍", "gender", "OTHER"), 200);
      assertThat(((Map<?, ?>) result.get("user")).get("displayName")).isEqualTo("新昵称");
      assertThat(((Map<?, ?>) result.get("profile")).get("school")).isEqualTo("学校");
      assertThat(patch(seller, Map.of("displayName", name), 200)).containsKey("user");
      assertThat(seller.call("GET", "/profile", null, null, 200)).containsEntry("school", "学校");
      for (var invalid : List.of(Map.<String, Object>of(), Map.<String, Object>of("role", "ADMIN"),
          Map.<String, Object>of("school", 123), Map.<String, Object>of("bio", "长".repeat(301)), Map.<String, Object>of("gender", "unknown")))
        patch(seller, invalid, 400);
      var admin = new Browser("admin");
      patch(admin, Map.of("school", "学校"), 403);
      assertThat(admin.call("GET", "/profile", null, null, 200)).containsEntry("id", "admin");
      admin.call("GET", "/users/admin/profile", null, null, 403);
      assertThat(patch(seller, Map.of("displayName", "字".repeat(80)), 200)).containsKey("user");
    } finally {
      patch(seller, Map.of("displayName", name, "school", "", "campus", "", "bio", "", "gender", ""), 200);
    }
  }

  @Test void profileFieldsUseTheSameEdgeWhitespaceAsTheBrowser() throws Exception {
    var buyer = new Browser("buyer");
    String name = (String) buyer.call("GET", "/profile", null, null, 200).get("displayName");
    try {
      patch(buyer, Map.of("displayName", "\u00a0\ufeff\u202f"), 400);
      var result = patch(buyer, Map.of("school", "\ufeff\u00a0校区\u00a0分部\u202f", "bio", "\u00a0\ufeff"), 200);
      assertThat(((Map<?, ?>) result.get("profile")).get("school")).isEqualTo("校区\u00a0分部");
      assertThat(((Map<?, ?>) result.get("profile")).get("bio")).isEqualTo("");
    } finally {
      patch(buyer, Map.of("displayName", name, "school", "", "bio", ""), 200);
    }
  }

  @Test void eachPageChecksContextAndListingsExposeOnlyApprovedAvailableOrSoldProducts() throws Exception {
    var buyer = new Browser("buyer");
    var guest = new Browser(null);
    String p = product();
    String sold = product();
    String offShelf = product();
    String locked = product();
    String pending = product();
    db.update("UPDATE products SET sale_status='SOLD' WHERE id=?", sold);
    db.update("UPDATE products SET sale_status='OFF_SHELF' WHERE id=?", offShelf);
    db.update("UPDATE products SET sale_status='LOCKED' WHERE id=?", locked);
    db.update("UPDATE products SET audit_status='PENDING' WHERE id=?", pending);
    var admin = new Browser("admin");
    for (String resource : List.of("profile", "reviews", "products")) {
      buyer.call("GET", "/users/seller/" + resource, null, null, 403);
      buyer.call("GET", "/users/other/" + resource + "?contextType=product&contextId=" + p, null, null, 403);
      buyer.call("GET", "/users/seller/" + resource + "?contextType=product&contextId=" + pending, null, null, 403);
      guest.call("GET", "/users/seller/" + resource + "?contextType=product&contextId=" + p, null, null, 401);
    }
    assertThat(admin.call("GET", profile("seller", "product", pending), null, null, 200)).containsKey("context");
    assertThat(buyer.call("GET", profile("seller", "product", p), null, null, 200)).containsEntry("context", null);
    assertThat(buyer.call("GET", "/users/buyer/profile", null, null, 200)).containsEntry("context", null);
    String products = "/users/seller/products?contextType=product&contextId=" + p;
    var soldPage = buyer.call("GET", products + "&status=SOLD", null, null, 200);
    assertThat((List<?>) soldPage.get("items")).isNotEmpty().allSatisfy(item -> {
      assertThat(((Map<?, ?>) item).get("auditStatus")).isEqualTo("APPROVED");
      assertThat(((Map<?, ?>) item).get("saleStatus")).isEqualTo("SOLD");
    });
    var availablePage = buyer.call("GET", products + "&status=AVAILABLE", null, null, 200);
    assertThat((List<?>) availablePage.get("items")).isNotEmpty().allSatisfy(item ->
        assertThat(((Map<?, ?>) item).get("saleStatus")).isEqualTo("AVAILABLE"));
    buyer.call("GET", products + "&status=LOCKED", null, null, 400);
    buyer.call("GET", products + "&page=0", null, null, 400);
  }

  @Test void reportsAndCasesGrantOnlyTheAppropriateStaffBusinessContext() throws Exception {
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    var other = new Browser("other");
    var admin = new Browser("admin");
    var support = new Browser("support");
    String p = product();
    String report = (String) other.post("/reports", Map.of("productId", p, "reason", "测试举报")).get("id");
    admin.call("GET", profile("other", "report", report), null, null, 200);
    admin.call("GET", profile("seller", "report", report), null, null, 200);
    admin.call("GET", profile("buyer", "report", report), null, null, 403);
    buyer.call("GET", profile("other", "report", report), null, null, 403);
    support.call("GET", profile("seller", "report", report), null, null, 403);
    assertThat(((List<?>) admin.call("GET", "/reports", null, null, 200).get("items"))).anySatisfy(item ->
        assertThat(((Map<?, ?>) item).get("sellerId")).isEqualTo("seller"));
    assertThat(((List<?>) other.call("GET", "/reports", null, null, 200).get("items"))).allSatisfy(item ->
        assertThat(((Map<?, ?>) item).containsKey("sellerId")).isFalse());
    String id = paid(buyer);
    buyer.call("GET", profile("seller", "order", id), null, null, 200);
    other.call("GET", profile("seller", "order", id), null, null, 403);
    support.call("GET", profile("seller", "order", id), null, null, 403);
    String caseId = (String) buyer.post("/cases", Map.of("orderId", id, "kind", "REFUND", "reason", "测试退款")).get("id");
    assertThat(support.call("GET", profile("buyer", "case", caseId), null, null, 200)).containsKey("context");
    support.call("GET", profile("seller", "order", id), null, null, 200);
    seller.call("GET", profile("buyer", "case", caseId), null, null, 200);
    support.call("GET", profile("other", "case", caseId), null, null, 403);
    admin.call("GET", profile("seller", "case", caseId), null, null, 403);
    other.call("GET", profile("seller", "case", caseId), null, null, 403);
  }

  @Test void authorizedStaffContextIncludesOnlyTheRequestedRecordAndItsHistory() throws Exception {
    var admin = new Browser("admin");
    var support = new Browser("support");
    var buyer = new Browser("buyer");
    var seller = new Browser("seller");
    String product = product();
    db.update("UPDATE products SET audit_status='PENDING' WHERE id=?", product);
    admin.post("/moderation/products/" + product, Map.of("version", 0, "decision", "APPROVED",
        "reason", "核对商品说明及图片"));
    String report = (String) buyer.post("/reports", Map.of("productId", product,
        "reason", "申请核对举报商品")).get("id");
    admin.post("/reports/" + report + "/resolve", Map.of("result", "举报记录已核对"));

    String order = paid(buyer);
    String caseId = (String) buyer.post("/cases", Map.of("orderId", order, "kind", "REFUND",
        "reason", "需要退款的申请理由")).get("id");
    seller.post("/cases/" + caseId + "/actions", Map.of("action", "REJECT", "reason", "卖家第一次处理"));
    buyer.post("/cases/" + caseId + "/actions", Map.of("action", "APPEAL", "reason", "买家申诉处理"));

    var moderation = admin.call("GET", profile("seller", "product", product), null, null, 200);
    assertThat(moderation.get("context").toString()).contains("审核通过", "核对商品说明及图片");
    var reportContext = admin.call("GET", profile("buyer", "report", report), null, null, 200);
    assertThat(reportContext.get("context").toString()).contains("申请核对举报商品", "举报记录已核对", "已处理", "/reports?status=ALL&q=" + report);
    var caseContext = support.call("GET", profile("seller", "case", caseId), null, null, 200);
    assertThat(caseContext.get("context").toString()).contains("需要退款的申请理由", "客服处理中",
        "卖家第一次处理", "买家申诉处理", "/orders/" + order);
    assertThat(support.call("GET", profile("buyer", "order", order), null, null, 200).get("context").toString())
        .contains("需要退款的申请理由").doesNotContain("申请核对举报商品");
    assertThat(buyer.call("GET", profile("seller", "case", caseId), null, null, 200))
        .containsEntry("context", null);
  }

  @Test void authorizedLookupOfMissingReportParticipantReturnsNotFound() throws Exception {
    String report = UUID.randomUUID().toString();
    db.update("INSERT INTO reports VALUES(?,?,?,'历史举报','RESOLVED',NULL,0)",
        report, product(), "missing-user");
    var path = profile("missing-user", "report", report);
    new Browser("buyer").call("GET", path, null, null, 403);
    assertThat(new Browser("admin").call("GET", path, null, null, 404))
        .containsEntry("code", "NOT_FOUND");
  }

  String uploadAvatar(Browser browser, byte[] image, int expected) throws Exception {
    String boundary = UUID.randomUUID().toString();
    byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nAVATAR\r\n--"
        + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var response = browser.client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files"))
        .header("X-CSRF-TOKEN", browser.csrf).header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, image, suffix))).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(expected);
    return (String) json.readValue(response.body(), Map.class).get("id");
  }

  int imageStatus(Browser browser, String avatar) throws Exception {
    return browser.client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/" + avatar)).GET().build(),
        HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test void onlyCurrentValidatedAvatarIsSharedWithAuthenticatedUsers() throws Exception {
    var seller = new Browser("seller");
    var buyer = new Browser("buyer");
    var guest = new Browser(null);
    var png = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", png);
    uploadAvatar(seller, new byte[]{1, 2, 3}, 409);
    String first = uploadAvatar(seller, png.toByteArray(), 200);
    String second = uploadAvatar(seller, png.toByteArray(), 200);
    assertThat(imageStatus(buyer, first)).isEqualTo(403);
    patch(buyer, Map.of("avatarId", first), 403);
    try {
      patch(seller, Map.of("avatarId", first), 200);
      assertThat(imageStatus(buyer, first)).isEqualTo(200);
      assertThat(imageStatus(guest, first)).isEqualTo(403);
      patch(seller, Map.of("avatarId", second), 200);
      assertThat(imageStatus(buyer, first)).isEqualTo(403);
      assertThat(imageStatus(seller, first)).isEqualTo(200);
      assertThat(imageStatus(buyer, second)).isEqualTo(200);
    } finally {
      patch(seller, cn.edu.campus.shared.Rows.map("avatarId", null), 200);
    }
  }
}
