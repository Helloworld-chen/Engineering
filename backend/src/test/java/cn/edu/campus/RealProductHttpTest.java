package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.campus.product.ProductPort;
import cn.edu.campus.shared.Actor;
import cn.edu.campus.shared.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"market.providers.identity=real", "market.providers.product=real"})
class RealProductHttpTest extends ApiTestSupport {
  private static final Actor ADMIN = new Actor("admin", "admin", "管理员", "ADMIN");

  @Autowired ProductPort products;

  @Test
  void retriesPublishSafelyAndEditingApprovedProductRequiresNewReview() throws Exception {
    var seller = new Browser("seller");
    var buyer = new Browser("buyer");
    var admin = new Browser("admin");
    String image = image("seller", 'a');
    String title = "M04审核测试-" + key();
    var input = content(title, List.of(image), 2800);
    String publishKey = key();
    var pending = seller.call("POST", "/products", input, publishKey, 200);
    String id = (String) pending.get("id");
    assertThat(seller.call("POST", "/products", input, publishKey, 200).get("id")).isEqualTo(id);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM products WHERE title=?", Integer.class, title))
        .isEqualTo(1);
    assertThat(db.queryForObject("SELECT linked_id FROM files WHERE id=?", String.class, image))
        .isEqualTo(id);
    assertThat(pending.get("auditStatus")).isEqualTo("PENDING");
    buyer.call("GET", "/products/" + id, null, null, 403);

    var approved = approve(admin, id, pending.get("version"), 200);
    var edit = new LinkedHashMap<>(content(title, List.of(image), 3200));
    edit.put("version", approved.get("version"));
    var changed = patch(seller, id, edit, 200);
    assertThat(changed.get("auditStatus")).isEqualTo("PENDING");
    assertThat(((Number) changed.get("price")).longValue()).isEqualTo(3200);
    assertThat(approve(admin, id, approved.get("version"), 409).get("code"))
        .isEqualTo("VERSION_CONFLICT");
    buyer.call("GET", "/products/" + id, null, null, 403);

    var reviewed = approve(admin, id, changed.get("version"), 200);
    var offShelf =
        patch(seller, id, Map.of("saleStatus", "OFF_SHELF", "version", reviewed.get("version")), 200);
    assertThat(offShelf.get("saleStatus")).isEqualTo("OFF_SHELF");
    assertThat(buyer.call("POST", "/orders", Map.of("productId", id), key(), 409).get("code"))
        .isEqualTo("PRODUCT_UNAVAILABLE");
    var onShelf =
        patch(seller, id, Map.of("saleStatus", "AVAILABLE", "version", offShelf.get("version")), 200);
    assertThat(onShelf.get("saleStatus")).isEqualTo("AVAILABLE");
    assertThat(onShelf.get("auditStatus")).isEqualTo("APPROVED");
  }

  @Test
  void refusesForeignImageAndRollsBackBothProductAndEarlierFileLink() throws Exception {
    var seller = new Browser("seller");
    var other = new Browser("other");
    String ownImage = image("seller", 'a');
    String foreignImage = image("other", 'z');
    String title = "M04图片绑定测试-" + key();
    seller.call("POST", "/products", content(title, List.of(ownImage, foreignImage), 2800), key(), 403);
    assertThat(db.queryForObject("SELECT COUNT(*) FROM products WHERE title=?", Integer.class, title))
        .isZero();
    assertThat(db.queryForObject("SELECT linked_id FROM files WHERE id=?", String.class, ownImage))
        .isNull();

    var created = seller.post("/products", content(title, List.of(ownImage), 2800));
    String id = (String) created.get("id");
    assertThat(patch(other, id, Map.of("saleStatus", "OFF_SHELF", "version", created.get("version")), 403)
        .get("code")).isEqualTo("FORBIDDEN");
    assertThat(patch(seller, id, Map.of("saleStatus", "OFF_SHELF"), 400).get("code"))
        .isEqualTo("INVALID_INPUT");
    assertThat(seller.call("GET", "/products/" + id, null, null, 200).get("saleStatus"))
        .isEqualTo("AVAILABLE");
  }

  @Test
  void adminTakedownBlocksBuyingAndRelistingUntilReapproved() throws Exception {
    var seller = new Browser("seller");
    var buyer = new Browser("buyer");
    var admin = new Browser("admin");
    String title = "takedown-" + key();
    var input = content(title, List.of(image("seller", 'a')), 2800);
    var pending = seller.post("/products", input);
    String id = (String) pending.get("id");
    var approved = approve(admin, id, pending.get("version"), 200);
    long version = ((Number) approved.get("version")).longValue();
    String reason = "举报核实违规";

    var owner = new Actor("seller", "seller", "卖家", "USER");
    assertThatThrownBy(() -> products.takedown(owner, id, reason, version))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code).isEqualTo("FORBIDDEN"));
    assertThatThrownBy(() -> products.takedown(ADMIN, id, reason, version - 1))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code).isEqualTo("VERSION_CONFLICT"));
    assertThatThrownBy(() -> products.takedown(ADMIN, id, " ", version))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code).isEqualTo("INVALID_REASON"));
    assertThat(seller.call("GET", "/products/" + id, null, null, 200).get("saleStatus"))
        .isEqualTo("AVAILABLE");

    products.takedown(ADMIN, id, reason, version);
    var takenDown = seller.call("GET", "/products/" + id, null, null, 200);
    assertThat(takenDown.get("saleStatus")).isEqualTo("OFF_SHELF");
    assertThat(takenDown.get("auditStatus")).isEqualTo("REJECTED");
    assertThat(((Number) takenDown.get("version")).longValue()).isEqualTo(version + 1);
    assertThat(buyer.call("GET", "/products?keyword=" + title, null, null, 200).get("items"))
        .isEqualTo(List.of());
    assertThat(buyer.call("POST", "/orders", Map.of("productId", id), key(), 409).get("code"))
        .isEqualTo("PRODUCT_UNAVAILABLE");
    assertThat(patch(seller, id, Map.of("saleStatus", "AVAILABLE", "version", version + 1), 409)
        .get("code")).isEqualTo("INVALID_STATE");

    var edit = new LinkedHashMap<>(input);
    edit.put("description", "整改后的商品描述");
    edit.put("version", version + 1);
    var resubmitted = patch(seller, id, edit, 200);
    assertThat(resubmitted.get("auditStatus")).isEqualTo("PENDING");
    assertThat(resubmitted.get("saleStatus")).isEqualTo("OFF_SHELF");
    var reviewed = approve(admin, id, resubmitted.get("version"), 200);
    var relisted = patch(seller, id, Map.of("saleStatus", "AVAILABLE", "version", reviewed.get("version")), 200);
    assertThat(relisted.get("saleStatus")).isEqualTo("AVAILABLE");
  }

  @Test
  void directOrderLocksProductCancellationReleasesItAndPaymentMarksItSold() throws Exception {
    var seller = new Browser("seller");
    var buyer = new Browser("buyer");
    var other = new Browser("other");
    String id = product();
    String firstOrder = (String) buyer.post("/orders", Map.of("productId", id)).get("id");
    var locked = seller.call("GET", "/products/" + id, null, null, 200);
    assertThat(locked.get("saleStatus")).isEqualTo("LOCKED");
    assertThat(other.call("POST", "/orders", Map.of("productId", id), key(), 409).get("code"))
        .isEqualTo("PRODUCT_UNAVAILABLE");
    assertThat(patch(seller, id, Map.of("saleStatus", "OFF_SHELF", "version", locked.get("version")), 409)
        .get("code")).isEqualTo("PRODUCT_BUSY");
    long lockedVersion = ((Number) locked.get("version")).longValue();
    assertThatThrownBy(() -> products.takedown(ADMIN, id, "举报核实违规", lockedVersion))
        .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code).isEqualTo("PRODUCT_BUSY"));
    assertThat(seller.call("GET", "/products/" + id, null, null, 200)).isEqualTo(locked);

    buyer.post("/orders/" + firstOrder + "/cancel", Map.of());
    assertThat(seller.call("GET", "/products/" + id, null, null, 200).get("saleStatus"))
        .isEqualTo("AVAILABLE");
    String secondOrder = (String) other.post("/orders", Map.of("productId", id)).get("id");
    other.post("/orders/" + secondOrder + "/payment", Map.of());
    var sold = seller.call("GET", "/products/" + id, null, null, 200);
    assertThat(sold.get("saleStatus")).isEqualTo("SOLD");
    assertThat(patch(seller, id, Map.of("saleStatus", "OFF_SHELF", "version", sold.get("version")), 409)
        .get("code")).isEqualTo("PRODUCT_BUSY");
    products.takedown(ADMIN, id, "举报核实违规", ((Number) sold.get("version")).longValue());
    assertThat(seller.call("GET", "/products/" + id, null, null, 200)).isEqualTo(sold);
  }

  private String key() {
    return UUID.randomUUID().toString();
  }

  private String image(String owner, char prefix) {
    String id = prefix + key().substring(1);
    db.update(
        "INSERT INTO files(id,owner_id,purpose,mime,filename,storage_name,byte_size,created_at)"
            + " VALUES(?,?,'PRODUCT','image/png','test.png',?,1,0)",
        id, owner, id + ".png");
    return id;
  }

  private Map<String, Object> content(String title, List<String> images, int cents) {
    return Map.of("title", title, "description", "商品描述", "category", "图书", "price", cents, "images", images);
  }

  private Map<String, Object> patch(Browser seller, String id, Map<String, Object> input, int status)
      throws Exception {
    return seller.call("PATCH", "/products/" + id, input, key(), status);
  }

  private Map<String, Object> approve(Browser admin, String id, Object version, int status)
      throws Exception {
    return admin.call(
        "POST", "/moderation/products/" + id,
        Map.of("decision", "APPROVED", "reason", "材料符合要求", "version", version),
        key(), status);
  }
}
