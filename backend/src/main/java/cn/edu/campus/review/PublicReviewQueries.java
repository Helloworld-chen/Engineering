package cn.edu.campus.review;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.shared.Rows;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PublicReviewQueries {
  private static final String SELECT =
      "SELECT r.id,u.display_name reviewer_name,"
          + " IF(r.subject_role='BUYER','SELLER','BUYER') reviewer_role,"
          + " r.subject_role,r.stars,r.comment,r.published_at,p.title product_title"
          + " FROM order_reviews r JOIN users u ON u.id=r.reviewer_id"
          + " JOIN orders o ON o.id=r.order_id JOIN products p ON p.id=o.product_id";
  private static final String NEWEST = " ORDER BY r.published_at DESC,r.id DESC";
  private final JdbcTemplate db;

  public PublicReviewQueries(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> forOrder(String orderId) {
    return db.queryForList(SELECT + " WHERE r.order_id=? AND r.published_at IS NOT NULL" + NEWEST,
        orderId).stream().map(Rows::view).toList();
  }

  public Map<String, Object> forSubject(String subjectId, String role, int page, int pageSize) {
    String filter = " WHERE r.subject_id=? AND r.published_at IS NOT NULL"
        + " AND (?='ALL' OR r.subject_role=?)";
    var items = db.queryForList(SELECT + filter + NEWEST + " LIMIT ? OFFSET ?",
        subjectId, role, role, pageSize, (long) (page - 1) * pageSize)
        .stream().map(Rows::view).toList();
    long total = db.queryForObject("SELECT COUNT(*) FROM order_reviews r" + filter, Long.class,
        subjectId, role, role);
    return map("items", items, "total", total, "page", page, "pageSize", pageSize);
  }

  public Map<String, Object> credit(String subjectId, String role) {
    Map<String, Object> distribution = map("1", 0, "2", 0, "3", 0, "4", 0, "5", 0);
    long count = 0;
    long sum = 0;
    for (var row : db.queryForList(
        "SELECT stars,COUNT(*) rating_count FROM order_reviews WHERE subject_id=? AND subject_role=?"
            + " AND published_at IS NOT NULL GROUP BY stars", subjectId, role)) {
      long stars = n(row, "stars");
      long occurrences = n(row, "rating_count");
      distribution.put(Long.toString(stars), occurrences);
      count += occurrences;
      sum += stars * occurrences;
    }
    if (count == 0) {
      return map("count", 0, "average", null, "grade", "暂无评价", "distribution", distribution);
    }
    BigDecimal average = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    int hundredths = average.movePointRight(2).intValueExact();
    String grade = hundredths >= 450 ? "优秀" : hundredths >= 400 ? "良好"
        : hundredths >= 300 ? "一般" : "待提升";
    return map("count", count, "average", average, "grade", grade, "distribution", distribution);
  }
}
