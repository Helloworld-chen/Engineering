package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M06 审核与举报流程回归：版本绑定、举报约束、处置下架与审计。 */
class ModerationFlowTest extends ApiTestSupport {

  private String product(String seller, String audit, String sale) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at)"
            + " VALUES(?,?,'待审商品','说明','数码',10000,'[]',?,?,0)",
        id,
        seller,
        audit,
        sale);
    return id;
  }

  private long count(String sql, Object... args) {
    Long n = db.queryForObject(sql, Long.class, args);
    return n == null ? 0 : n;
  }

  @Test
  void reviewBindsDecisionToReviewedVersionAndWritesAudit() throws Exception {
    String id = product("seller", "PENDING", "AVAILABLE");
    var admin = new Browser("admin");

    var result = admin.post("/moderation/products/" + id, Map.of("decision", "APPROVED", "reason", "资料齐全", "version", 0));
    assertThat(result.get("auditStatus")).isEqualTo("APPROVED");

    assertThat((long) db.queryForObject("SELECT reviewed_version FROM moderation_reviews WHERE product_id=?", Long.class, id))
        .isEqualTo(0L);
    assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='PRODUCT_REVIEW' AND aggregate_id=?", id))
        .isEqualTo(1);
  }

  @Test
  void reviewWithStaleVersionIsRejected() throws Exception {
    String id = product("seller", "PENDING", "AVAILABLE");
    db.update("UPDATE products SET version=3 WHERE id=?", id);
    var admin = new Browser("admin");

    admin.call(
        "POST",
        "/moderation/products/" + id,
        Map.of("decision", "APPROVED", "reason", "资料齐全", "version", 0),
        UUID.randomUUID().toString(),
        409);
    assertThat(count("SELECT COUNT(*) FROM moderation_reviews WHERE product_id=?", id)).isZero();
  }

  @Test
  void onlyPublicProductsCanBeReported() throws Exception {
    String pending = product("seller", "PENDING", "AVAILABLE");
    String offShelf = product("seller", "APPROVED", "OFF_SHELF");
    var buyer = new Browser("buyer");

    buyer.call("POST", "/reports", Map.of("productId", pending, "reason", "违规"), UUID.randomUUID().toString(), 409);
    buyer.call("POST", "/reports", Map.of("productId", offShelf, "reason", "违规"), UUID.randomUUID().toString(), 409);
    assertThat(count("SELECT COUNT(*) FROM reports")).isZero();
  }

  @Test
  void sellerCannotReportOwnProduct() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var seller = new Browser("seller");
    seller.call("POST", "/reports", Map.of("productId", id, "reason", "自举报"), UUID.randomUUID().toString(), 409);
  }

  @Test
  void duplicatePendingReportIsMergedIntoOne() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var buyer = new Browser("buyer");

    var first = buyer.post("/reports", Map.of("productId", id, "reason", "描述不符"));
    var second = buyer.post("/reports", Map.of("productId", id, "reason", "描述不符"));

    assertThat(second.get("id")).isEqualTo(first.get("id"));
    assertThat(second.get("duplicated")).isEqualTo(true);
    assertThat(count("SELECT COUNT(*) FROM reports WHERE product_id=?", id)).isEqualTo(1);
  }

  @Test
  void violationTakesDownOnSaleProductAndForcesReReview() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var buyer = new Browser("buyer");
    var reportId = (String) buyer.post("/reports", Map.of("productId", id, "reason", "货不对板")).get("id");

    var admin = new Browser("admin");
    var resolved = admin.post("/reports/" + reportId + "/resolve", Map.of("result", "确认违规，下架处理"));

    assertThat(resolved.get("status")).isEqualTo("RESOLVED");
    assertThat(resolved.get("decision")).isEqualTo("VIOLATION");
    assertThat(resolved.get("productTakedown")).isEqualTo(true);
    assertThat(db.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("OFF_SHELF");
    // 下架同时置审核结论为已驳回，避免卖家自行上架撤销处置
    assertThat(db.queryForObject("SELECT audit_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("REJECTED");
    assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='REPORT_RESOLVE' AND aggregate_id=?", reportId))
        .isEqualTo(1);

    // 结论落库并随列表返回，前端可直接展示「确认违规」而不必解析文本
    assertThat(db.queryForObject("SELECT decision FROM reports WHERE id=?", String.class, reportId))
        .isEqualTo("VIOLATION");
    var list = admin.call("GET", "/reports?status=RESOLVED", null, null, 200);
    assertThat((List<Map<String, Object>>) list.get("items"))
        .filteredOn(r -> reportId.equals(r.get("id")))
        .singleElement()
        .satisfies(r -> assertThat(r.get("decision")).isEqualTo("VIOLATION"));

    // 卖家自行点上架会被拒绝：必须改内容重新审核通过后才能恢复销售
    long version = db.queryForObject("SELECT version FROM products WHERE id=?", Long.class, id);
    var relist =
        new Browser("seller")
            .call(
                "PATCH",
                "/products/" + id,
                Map.of("saleStatus", "AVAILABLE", "version", version),
                UUID.randomUUID().toString(),
                409);
    assertThat(relist.get("code")).isEqualTo("INVALID_STATE");
    assertThat(db.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("OFF_SHELF");
    var page = new Browser(null).call("GET", "/products?keyword=待审商品", null, null, 200);
    assertThat((List<Map<String, Object>>) page.get("items"))
        .noneSatisfy(item -> assertThat(item.get("id")).isEqualTo(id));
  }

  @Test
  void violationOnLockedProductKeepsStatusForRunningOrder() throws Exception {
    String id = product("seller", "APPROVED", "LOCKED");
    var buyer = new Browser("buyer");
    var reportId = (String) buyer.post("/reports", Map.of("productId", id, "reason", "货不对板")).get("id");

    var resolved =
        new Browser("admin").post("/reports/" + reportId + "/resolve", Map.of("result", "确认违规"));

    assertThat(resolved.get("decision")).isEqualTo("VIOLATION");
    // 锁定涉及在途订单，保留原状态，处置只落结论
    assertThat(resolved.get("productTakedown")).isEqualTo(false);
    assertThat(db.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("LOCKED");
    assertThat(db.queryForObject("SELECT audit_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("APPROVED");
  }

  @Test
  void dismissedReportStoresDismissedDecision() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var buyer = new Browser("buyer");
    var reportId = (String) buyer.post("/reports", Map.of("productId", id, "reason", "疑似假冒")).get("id");

    new Browser("admin").post("/reports/" + reportId + "/resolve", Map.of("result", "举报不成立"));

    assertThat(db.queryForObject("SELECT decision FROM reports WHERE id=?", String.class, reportId))
        .isEqualTo("DISMISSED");
  }

  @Test
  void violationOnPendingProductIsRejectedThroughModerate() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var buyer = new Browser("buyer");
    var reportId = (String) buyer.post("/reports", Map.of("productId", id, "reason", "货不对板")).get("id");
    // 卖家在处置前改内容，商品回到待审核
    db.update("UPDATE products SET audit_status='PENDING' WHERE id=?", id);

    var admin = new Browser("admin");
    var resolved = admin.post("/reports/" + reportId + "/resolve", Map.of("result", "确认违规"));

    assertThat(resolved.get("productTakedown")).isEqualTo(true);
    assertThat(db.queryForObject("SELECT audit_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("REJECTED");
  }

  @Test
  void dismissedResolutionKeepsProductOnSale() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var buyer = new Browser("buyer");
    var reportId = (String) buyer.post("/reports", Map.of("productId", id, "reason", "疑似假冒")).get("id");

    var admin = new Browser("admin");
    var resolved = admin.post("/reports/" + reportId + "/resolve", Map.of("result", "举报不成立"));

    assertThat(resolved.get("decision")).isEqualTo("DISMISSED");
    assertThat(resolved.get("productTakedown")).isEqualTo(false);
    assertThat(db.queryForObject("SELECT sale_status FROM products WHERE id=?", String.class, id))
        .isEqualTo("AVAILABLE");
  }

  @Test
  void resolvedReportCannotBeResolvedTwice() throws Exception {
    String id = product("seller", "APPROVED", "AVAILABLE");
    var buyer = new Browser("buyer");
    var reportId = (String) buyer.post("/reports", Map.of("productId", id, "reason", "重复处置")).get("id");

    var admin = new Browser("admin");
    admin.post("/reports/" + reportId + "/resolve", Map.of("result", "举报不成立"));
    admin.call(
        "POST",
        "/reports/" + reportId + "/resolve",
        Map.of("result", "举报不成立"),
        UUID.randomUUID().toString(),
        409);
  }

  @Test
  void reporterOnlySeesOwnReportsWhileAdminSeesAll() throws Exception {
    String first = product("seller", "APPROVED", "AVAILABLE");
    String second = product("seller", "APPROVED", "AVAILABLE");
    new Browser("buyer").post("/reports", Map.of("productId", first, "reason", "甲"));
    new Browser("other").post("/reports", Map.of("productId", second, "reason", "乙"));

    var mine = new Browser("buyer").call("GET", "/reports", null, null, 200);
    assertThat((List<Map<String, Object>>) mine.get("items")).hasSize(1);

    var all = new Browser("admin").call("GET", "/reports", null, null, 200);
    assertThat((List<Map<String, Object>>) all.get("items")).hasSize(2);
  }
}
