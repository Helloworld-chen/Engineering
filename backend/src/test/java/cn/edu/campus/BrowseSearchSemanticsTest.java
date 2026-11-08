package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M05 检索语义回归：通配符、可见性、分页边界。 */
class BrowseSearchSemanticsTest extends ApiTestSupport {

  private String product(String seller, String title, String category, String audit, String sale) {
    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at)"
            + " VALUES(?,?,?,'说明',?,10000,'[]',?,?,0)",
        id,
        seller,
        title,
        category,
        audit,
        sale);
    return id;
  }

  @Test
  void wildcardsInKeywordAreMatchedLiterally() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    product("seller", tag + "100%羊毛", "books", "APPROVED", "AVAILABLE");
    product("seller", tag + "100羊毛", "books", "APPROVED", "AVAILABLE");

    var visitor = new Browser(null);
    // 未转义时 "100%" 会退化成 "100" + 任意后缀，把两条都匹配出来
    var percent = visitor.call("GET", "/products?keyword=" + tag + "100%25", null, null, 200);
    assertThat(((Number) percent.get("total")).longValue()).isEqualTo(1);

    var underscore = visitor.call("GET", "/products?keyword=" + tag + "100_", null, null, 200);
    assertThat(((Number) underscore.get("total")).longValue()).isZero();
  }

  @Test
  void onlyApprovedAndAvailableOrLockedProductsArePublic() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    product("seller", tag + "-公开", "books", "APPROVED", "AVAILABLE");
    product("seller", tag + "-锁定", "books", "APPROVED", "LOCKED");
    product("seller", tag + "-待审", "books", "PENDING", "AVAILABLE");
    product("seller", tag + "-驳回", "books", "REJECTED", "AVAILABLE");
    product("seller", tag + "-下架", "books", "APPROVED", "OFF_SHELF");
    product("seller", tag + "-已售", "books", "APPROVED", "SOLD");

    var page = new Browser(null).call("GET", "/products?keyword=" + tag, null, null, 200);
    assertThat(((Number) page.get("total")).longValue()).isEqualTo(2);
    assertThat((List<Map<String, Object>>) page.get("items"))
        .extracting(item -> item.get("title"))
        .containsExactlyInAnyOrder(tag + "-公开", tag + "-锁定");
  }

  @Test
  void pageSizeIsClampedAndEchoedBack() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    product("seller", tag, "books", "APPROVED", "AVAILABLE");

    var page = new Browser(null).call("GET", "/products?keyword=" + tag + "&pageSize=1000", null, null, 200);
    assertThat(((Number) page.get("pageSize")).intValue()).isEqualTo(100);
    assertThat(((Number) page.get("page")).intValue()).isEqualTo(1);
  }

  @Test
  void categoryFilterAndTotalStayConsistent() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    product("seller", tag + "-书", "books", "APPROVED", "AVAILABLE");
    product("seller", tag + "-数码", "digital", "APPROVED", "AVAILABLE");

    var filtered = new Browser(null).call("GET", "/products?keyword=" + tag + "&category=books", null, null, 200);
    assertThat(((Number) filtered.get("total")).longValue()).isEqualTo(1);
    assertThat((List<Map<String, Object>>) filtered.get("items")).hasSize(1);

    var all = new Browser(null).call("GET", "/products?keyword=" + tag, null, null, 200);
    assertThat(((Number) all.get("total")).longValue()).isEqualTo(2);
  }
}
