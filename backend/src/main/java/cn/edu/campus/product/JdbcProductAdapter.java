package cn.edu.campus.product;

import static cn.edu.campus.shared.BusinessException.*;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.files.FilePort;
import cn.edu.campus.notification.TimePort;
import cn.edu.campus.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class JdbcProductAdapter implements ProductPort {
  private record ProductDetails(
      String title, String description, String category, long price, List<String> images) {}

  private final JdbcTemplate db;
  private final TimePort clock;
  private final FilePort files;

  JdbcProductAdapter(JdbcTemplate db, TimePort clock, FilePort files) {
    this.db = db;
    this.clock = clock;
    this.files = files;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> lock(String id) {
    var r = db.queryForList("SELECT * FROM products WHERE id=? FOR UPDATE", id);
    if (r.isEmpty()) throw new BusinessException("NOT_FOUND", "商品不存在", 404);
    return r.getFirst();
  }

  @Override
  public Map<String, Object> get(String id, Actor actor) {
    var rows =
        db.queryForList(
            "SELECT p.*,u.display_name seller_name FROM products p JOIN users u ON u.id=p.seller_id"
                + " WHERE p.id=?",
            id);
    if (rows.isEmpty()) throw new BusinessException("NOT_FOUND", "商品不存在", 404);
    var r = rows.getFirst();
    allowed(
        "APPROVED".equals(s(r, "audit_status"))
            || actor != null && (actor.id().equals(s(r, "seller_id")) || actor.is("ADMIN")));
    var v = view(r);
    v.put("images", strings(r.get("images")));
    return v;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void updateSale(String id, String state) {
    String expected =
        switch (Objects.toString(state, "")) {
          case "LOCKED" -> "AVAILABLE";
          case "AVAILABLE", "SOLD" -> "LOCKED";
          default -> throw new BusinessException("INVALID_STATE", "交易商品状态不正确", 409);
        };
    require(
        db.update(
                "UPDATE products SET sale_status=?,version=version+1 WHERE id=? AND sale_status=?"
                    + " AND (? <> 'LOCKED' OR audit_status='APPROVED')",
                state, id, expected, state)
            == 1,
        "INVALID_STATE",
        "商品状态已变化，请刷新");
  }

  private ProductDetails details(Map<String, Object> in) {
    if (!(in.get("title") instanceof String) || !(in.get("description") instanceof String)
        || !(in.get("category") instanceof String) || !(in.get("price") instanceof Number)) {
      throw new BusinessException("INVALID_INPUT", "请填写商品名称、描述、分类和价格", 400);
    }
    long price = n(in, "price");
    require(
        price > 0 && price <= 100_000_000, "INVALID_PRICE", "商品价格需大于0且不超过100万元");
    if (!(in.get("images") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 6) {
      throw new BusinessException("INVALID_IMAGES", "请上传1至6张商品照片", 409);
    }
    var images = new ArrayList<String>(raw.size());
    var seen = new HashSet<String>();
    for (Object item : raw) {
      if (!(item instanceof String image) || image.isBlank() || !seen.add(image)) {
        throw new BusinessException("INVALID_IMAGES", "图片标识无效或重复", 409);
      }
      images.add(image);
    }
    return new ProductDetails(
        text(in, "title", 1, 120), text(in, "description", 1, 5000), text(in, "category", 1, 40),
        price, images);
  }

  @Override
  @Transactional
  public Map<String, Object> create(Actor a, Map<String, Object> in) {
    allowed(a.is("USER"));
    var details = details(in);
    String id = id();
    db.update(
        "INSERT INTO"
            + " products(id,seller_id,title,description,category,price,images,audit_status,sale_status,created_at)"
            + " VALUES(?,?,?,?,?,?,?,'PENDING','AVAILABLE',?)",
        id,
        a.id(),
        details.title(),
        details.description(),
        details.category(),
        details.price(),
        json(details.images()),
        clock.now());
    files.link(a, details.images(), "PRODUCT", "PRODUCT", id);
    return get(id, a);
  }

  @Override
  @Transactional
  public Map<String, Object> update(Actor a, String id, Map<String, Object> in) {
    var r = lock(id);
    allowed(a.is("USER") && a.id().equals(s(r, "seller_id")));
    if (!(in.get("version") instanceof Number)) {
      throw new BusinessException("INVALID_INPUT", "请提供商品版本", 400);
    }
    require(n(in, "version") == n(r, "version"), "VERSION_CONFLICT", "商品已更新，请刷新");
    require(!Set.of("LOCKED", "SOLD").contains(s(r, "sale_status")), "PRODUCT_BUSY", "商品已被占用或售出");
    if (in.containsKey("saleStatus")) {
      if (!in.keySet().equals(Set.of("saleStatus", "version"))) {
        throw new BusinessException("INVALID_INPUT", "上下架与商品内容修改请分开提交", 400);
      }
      String state = s(in, "saleStatus");
      require(Set.of("AVAILABLE", "OFF_SHELF").contains(state), "INVALID_STATE", "商品状态不正确");
      if (!state.equals(s(r, "sale_status"))) {
        if ("AVAILABLE".equals(state)) {
          require("APPROVED".equals(s(r, "audit_status")), "INVALID_STATE", "商品审核通过后才能重新上架");
        }
        db.update("UPDATE products SET sale_status=?,version=version+1 WHERE id=?", state, id);
      }
    } else {
      var details = details(in);
      db.update(
          "UPDATE products SET"
              + " title=?,description=?,category=?,price=?,images=?,audit_status='PENDING',version=version+1"
              + " WHERE id=?",
          details.title(),
          details.description(),
          details.category(),
          details.price(),
          json(details.images()),
          id);
      files.link(a, details.images(), "PRODUCT", "PRODUCT", id);
    }
    return get(id, a);
  }

  @Override
  @Transactional
  public void moderate(Actor a, String id, String decision, String reason, long version) {
    allowed(a.is("ADMIN"));
    var r = lock(id);
    require(version == n(r, "version"), "VERSION_CONFLICT", "商品版本已变化");
    require("PENDING".equals(s(r, "audit_status")), "INVALID_STATE", "商品不在待审核状态");
    require("APPROVED".equals(decision) || "REJECTED".equals(decision),
        "INVALID_DECISION", "审核结论不正确");
    db.update("UPDATE products SET audit_status=?,version=version+1 WHERE id=?", decision, id);
    clock.emit(
        s(r, "seller_id"),
        id,
        "PRODUCT_REVIEW",
        "商品审核结果：" + (decision.equals("APPROVED") ? "通过" : "驳回"));
  }

  @Override
  @Transactional
  public void takedown(Actor admin, String id, String reason, long version) {
    allowed(admin != null && admin.is("ADMIN"));
    if (reason == null || reason.isBlank() || reason.length() > 2000) {
      throw new BusinessException("INVALID_REASON", "请填写有效的下架原因", 400);
    }
    var r = lock(id);
    require(version == n(r, "version"), "VERSION_CONFLICT", "商品已更新，请刷新");
    String saleStatus = s(r, "sale_status");
    require(!"LOCKED".equals(saleStatus), "PRODUCT_BUSY", "商品有进行中的订单，暂不能下架");
    if ("AVAILABLE".equals(saleStatus) && "APPROVED".equals(s(r, "audit_status"))) {
      db.update(
          "UPDATE products SET sale_status='OFF_SHELF',audit_status='REJECTED',version=version+1 WHERE id=?",
          id);
    }
  }

  @Override
  public List<Map<String, Object>> mine(Actor a) {
    return db
        .queryForList("SELECT * FROM products WHERE seller_id=? ORDER BY created_at DESC", a.id())
        .stream()
        .map(
            r -> {
              var v = view(r);
              v.put("images", strings(r.get("images")));
              return v;
            })
        .toList();
  }
}
