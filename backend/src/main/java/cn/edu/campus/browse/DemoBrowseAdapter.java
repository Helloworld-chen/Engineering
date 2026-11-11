package cn.edu.campus.browse;

import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.notification.TimePort;
import cn.edu.campus.shared.Actor;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * M05 商品浏览检索。
 *
 * <p>规则来源：个人需求 Z01（列表与详情）、Z02（检索与分页）、NFR-Z01（P95≤2s）、NFR-Z03（可见性 0 泄漏）。
 *
 * <p>与旧实现的差异：
 *
 * <ul>
 *   <li>关键词按字面量匹配：先转义 {@code %}、{@code _}、{@code \} 再拼 LIKE，避免用户输入通配符导致"返回全部"。
 *   <li>计数与列表共用同一份 WHERE 与同一组参数，杜绝两处拼接走样导致 total 与 items 不一致。
 *   <li>公开可见性收敛为常量 {@link #PUBLIC_WHERE}，与 M04 的 audit_status / sale_status 取值一一对应。
 *   <li>登录用户排除本人商品（由服务端会话解析，前端不传用户编号）；游客查询全部公开商品。
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "market.providers.browse", havingValue = "demo")
public class DemoBrowseAdapter implements BrowsePort {

  /** 公开可见：已审核通过，且处于可售或已被他人锁定（锁定期间仍在列表可见，只是不可下单）。 */
  static final String PUBLIC_WHERE =
      " WHERE audit_status='APPROVED' AND sale_status IN ('AVAILABLE','LOCKED')";

  static final int MAX_PAGE_SIZE = 100;
  static final int MAX_KEYWORD = 60;
  static final int MAX_CATEGORY = 40;

  private static final char ESCAPE = '\\';

  private final JdbcTemplate db;
  private final TimePort clock;

  public DemoBrowseAdapter(JdbcTemplate db, TimePort clock) {
    this.db = db;
    this.clock = clock;
  }

  @Override
  public Map<String, Object> search(
      Actor viewer, String keyword, String category, int page, int pageSize) {
    int safePage = Math.max(1, page);
    int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));

    String kw = trim(keyword, MAX_KEYWORD);
    String cat = trim(category, MAX_CATEGORY);
    String viewerId = viewer == null ? "" : viewer.id();

    StringBuilder where = new StringBuilder(PUBLIC_WHERE);
    List<Object> args = new ArrayList<>();
    if (!kw.isEmpty()) {
      where.append(" AND title LIKE ?");
      args.add("%" + escapeLike(kw) + "%");
    }
    if (!cat.isEmpty()) {
      where.append(" AND category=?");
      args.add(cat);
    }
    if (!viewerId.isEmpty()) {
      where.append(" AND seller_id<>?");
      args.add(viewerId);
    }
    String whereSql = where.toString();

    Long total =
        db.queryForObject("SELECT COUNT(*) FROM products" + whereSql, Long.class, args.toArray());

    List<Object> listArgs = new ArrayList<>(args);
    listArgs.add(safeSize);
    listArgs.add((long) (safePage - 1) * safeSize);

    var items =
        db.queryForList(
                "SELECT * FROM products"
                    + whereSql
                    + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                listArgs.toArray())
            .stream()
            .map(
                r -> {
                  var v = view(r);
                  v.put("images", strings(r.get("images")));
                  // 列表项直接给出「是否可下单」的口径，前端据此置灰按钮，不自行推断
                  v.put(
                      "purchasable",
                      "APPROVED".equals(s(r, "audit_status"))
                          && "AVAILABLE".equals(s(r, "sale_status")));
                  return v;
                })
            .toList();

    return map(
        "items",
        items,
        "total",
        total == null ? 0L : total,
        "page",
        safePage,
        "pageSize",
        safeSize,
        "serverTime",
        instant(clock.now()));
  }

  private static String trim(String value, int max) {
    if (value == null) return "";
    String v = value.trim();
    return v.length() > max ? v.substring(0, max) : v;
  }

  /** 把 LIKE 的通配符转义为字面量。MySQL 的 LIKE 默认以反斜杠为转义符，故先转义反斜杠本身。 */
  static String escapeLike(String raw) {
    StringBuilder sb = new StringBuilder(raw.length() + 8);
    for (char c : raw.toCharArray()) {
      if (c == ESCAPE || c == '%' || c == '_') sb.append(ESCAPE);
      sb.append(c);
    }
    return sb.toString();
  }
}
