package cn.edu.campus.identity;

import static cn.edu.campus.shared.BusinessException.allowed;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.files.FilePort;
import cn.edu.campus.review.PublicReviewQueries;
import cn.edu.campus.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class UserProfileService {
  private static final Set<String> FIELDS =
      Set.of("displayName", "avatarId", "school", "campus", "bio", "gender");
  private static final Set<String> STAFF_FIELDS = Set.of("displayName", "avatarId");
  private static final int PAGE_SIZE = 20;
  private final JdbcTemplate db;
  private final IdentityPort users;
  private final FilePort files;
  private final ProfileContext contexts;
  private final PublicReviewQueries publicReviews;

  UserProfileService(JdbcTemplate db, IdentityPort users, FilePort files, ProfileContext contexts,
      PublicReviewQueries publicReviews) {
    this.db = db;
    this.users = users;
    this.files = files;
    this.contexts = contexts;
    this.publicReviews = publicReviews;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> own(Actor actor) {
    return profile(users.find(actor.id()));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Map<String, Object> update(Actor actor, Map<String, Object> input) {
    if (input.isEmpty() || !FIELDS.containsAll(input.keySet())) {
      throw new BusinessException("INVALID_INPUT", "请提供可修改的资料字段", 400);
    }
    allowed(actor.is("USER") || STAFF_FIELDS.containsAll(input.keySet()));
    // Serialize partial patches, including patches to a profile that has no optional data yet.
    db.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", String.class, actor.id());
    var currentActor = users.find(actor.id());
    var current = profile(currentActor);
    String school = field(input, current, "school", 0, 80);
    String campus = field(input, current, "campus", 0, 80);
    String bio = field(input, current, "bio", 0, 300);
    String gender = field(input, current, "gender", 0, 8);
    if (!Set.of("", "MALE", "FEMALE", "OTHER").contains(gender)) {
      throw new BusinessException("INVALID_INPUT", "性别选项不正确", 400);
    }
    String avatar = (String) current.get("avatarId");
    if (input.containsKey("avatarId")) {
      avatar = input.get("avatarId") == null ? null : field(input, current, "avatarId", 1, 36);
      if (avatar != null) files.link(actor, List.of(avatar), "AVATAR", "AVATAR", actor.id());
    }
    var updated = input.containsKey("displayName")
        ? users.updateProfile(actor, field(input, current, "displayName", 1, 80))
        : currentActor;
    db.update(
        "INSERT INTO user_profiles(user_id,avatar_id,school,campus,bio,gender) VALUES(?,?,?,?,?,?)"
            + " AS incoming ON DUPLICATE KEY UPDATE avatar_id=incoming.avatar_id,"
            + " school=incoming.school,campus=incoming.campus,bio=incoming.bio,gender=incoming.gender",
        actor.id(), avatar, school, campus, bio, gender);
    return map("user", updated, "profile", profile(updated));
  }

  @Transactional(readOnly = true)
  public Map<String, Object> view(Actor viewer, String id, String type, String contextId) {
    var context = contexts.authorize(viewer, id, type, contextId, true);
    var target = requireUser(id);
    var result = profile(target);
    result.put("buyerCredit", publicReviews.credit(target.id(), "BUYER"));
    result.put("sellerCredit", publicReviews.credit(target.id(), "SELLER"));
    result.put("context", context);
    return result;
  }

  @Transactional(readOnly = true)
  public Map<String, Object> products(
      Actor viewer, String id, String type, String contextId, int page, String status) {
    contexts.authorize(viewer, id, type, contextId, false);
    var target = requireUser(id);
    validateList(page, status, Set.of("ALL", "AVAILABLE", "SOLD"));
    String filter = " WHERE p.seller_id=? AND p.audit_status='APPROVED'"
        + " AND p.sale_status IN ('AVAILABLE','SOLD') AND (?='ALL' OR p.sale_status=?)";
    var items = db.queryForList("SELECT p.*,u.display_name seller_name FROM products p"
        + " JOIN users u ON u.id=p.seller_id" + filter + " ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",
        target.id(), status, status, PAGE_SIZE, (long) (page - 1) * PAGE_SIZE)
        .stream().map(row -> {
          var item = Rows.view(row);
          item.put("images", strings(row.get("images")));
          return item;
        }).toList();
    long total = db.queryForObject("SELECT COUNT(*) FROM products p" + filter,
        Long.class, target.id(), status, status);
    return map("items", items, "total", total, "page", page, "pageSize", PAGE_SIZE);
  }

  @Transactional(readOnly = true)
  public Map<String, Object> reviews(
      Actor viewer, String id, String type, String contextId, int page, String role) {
    contexts.authorize(viewer, id, type, contextId, false);
    var target = requireUser(id);
    validateList(page, role, Set.of("ALL", "BUYER", "SELLER"));
    return publicReviews.forSubject(target.id(), role, page, PAGE_SIZE);
  }

  private Map<String, Object> profile(Actor actor) {
    var optional = db.queryForList("SELECT avatar_id,school,campus,bio,gender"
        + " FROM user_profiles WHERE user_id=?", actor.id());
    Map<String, Object> details = optional.isEmpty() ? Map.of() : optional.getFirst();
    Object avatar = details.get("avatar_id");
    return map("id", actor.id(), "displayName", actor.displayName(), "avatarId", avatar,
        "avatarUrl", avatar == null ? null : "/api/v1/files/" + avatar, "school", s(details, "school"),
        "campus", s(details, "campus"), "bio", s(details, "bio"), "gender", s(details, "gender"));
  }

  private Actor requireUser(String id) {
    Actor user = null;
    try {
      user = users.find(id);
    } catch (BusinessException error) {
      if (!"UNAUTHENTICATED".equals(error.code)) throw error;
    }
    if (user == null || !user.is("USER")) {
      throw new BusinessException("NOT_FOUND", "普通用户不存在", 404);
    }
    return user;
  }

  private static String field(Map<String, Object> input, Map<String, Object> current,
      String key, int min, int max) {
    if (!input.containsKey(key)) return s(current, key);
    if (!(input.get(key) instanceof String raw)) {
      throw new BusinessException("INVALID_INPUT", key + "须为文本", 400);
    }
    String value = trimWebInput(raw);
    int length = value.codePointCount(0, value.length());
    if (length < min || length > max) {
      throw new BusinessException("INVALID_INPUT", key + "长度应为" + min + "至" + max + "个字符", 400);
    }
    return value;
  }

  private static void validateList(int page, String filter, Set<String> values) {
    if (page < 1 || !values.contains(filter)) {
      throw new BusinessException("INVALID_INPUT", "页码或筛选选项不正确", 400);
    }
  }
}
