package cn.edu.campus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BrowseOwnershipTest extends ApiTestSupport {
  private String item(String seller, String keyword, String category, long time) {
    String id = UUID.randomUUID().toString();
    db.update("INSERT INTO products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at) VALUES(?,?,?,?,?,100,'[]','APPROVED','AVAILABLE',?)",
        id, seller, keyword, "浏览归属回归", category, time);
    return id;
  }

  @Test
  void ownProductsAreExcludedBeforeCountingFilteringAndPagination() throws Exception {
    String keyword = UUID.randomUUID().toString();
    item("seller", keyword, "books", 30);
    String first = item("buyer", keyword, "books", 20);
    String second = item("other", keyword, "books", 10);
    item("buyer", keyword, "other", 40);
    var viewer = new Browser("seller");
    String query = "/products?keyword=" + keyword + "&category=books&pageSize=1&page=";
    var page1 = viewer.call("GET", query + "1", null, null, 200);
    var page2 = viewer.call("GET", query + "2", null, null, 200);
    assertThat(((Number) page1.get("total")).longValue()).isEqualTo(2);
    assertThat(((List<Map<String, Object>>) page1.get("items")).getFirst().get("id")).isEqualTo(first);
    assertThat(((List<Map<String, Object>>) page2.get("items")).getFirst().get("id")).isEqualTo(second);
  }

  @Test
  void visitorsSeeAllOwnersAndMembersSeeOnlyOtherOwners() throws Exception {
    String keyword = UUID.randomUUID().toString();
    for (String owner : List.of("buyer", "seller", "other")) item(owner, keyword, "books", 1);
    var visitor = new Browser(null).call("GET", "/products?keyword=" + keyword, null, null, 200);
    assertThat(((Number) visitor.get("total")).longValue()).isEqualTo(3);
    for (String owner : List.of("buyer", "seller", "other")) {
      var result = new Browser(owner).call("GET", "/products?keyword=" + keyword, null, null, 200);
      assertThat(((Number) result.get("total")).longValue()).isEqualTo(2);
      assertThat((List<Map<String, Object>>) result.get("items"))
          .allSatisfy(product -> assertThat(product.get("sellerId")).isNotEqualTo(owner));
    }
  }

  @Test
  void ownProductsRemainAccessibleThroughManagementAndDetail() throws Exception {
    String id = item("seller", UUID.randomUUID().toString(), "books", 1);
    var owner = new Browser("seller");
    var mine = owner.call("GET", "/my/products", null, null, 200);
    assertThat((List<Map<String, Object>>) mine.get("items"))
        .anySatisfy(product -> assertThat(product.get("id")).isEqualTo(id));
    assertThat(owner.call("GET", "/products/" + id, null, null, 200).get("sellerId")).isEqualTo("seller");
  }
}
