package cn.edu.campus.review;

import static cn.edu.campus.shared.BusinessException.allowed;
import static cn.edu.campus.shared.Rows.*;

import cn.edu.campus.identity.Sessions;
import cn.edu.campus.shared.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;

@RestController
@RequestMapping("/api/v1/orders/{id}/reviews")
class OrderReviewController {
  private static final ObjectReader REVIEW_INPUT =
      JSON.readerFor(new TypeReference<Map<String, Object>>() {})
          .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private final Sessions sessions;
  private final Idempotency idem;
  private final OrderReviewService reviews;

  OrderReviewController(Sessions sessions, Idempotency idem, OrderReviewService reviews) {
    this.sessions = sessions;
    this.idem = idem;
    this.reviews = reviews;
  }

  @GetMapping
  Map<String, Object> get(@PathVariable String id, HttpServletRequest request) {
    return reviews.get(sessions.require(request), id);
  }

  @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"})
  Map<String, Object> submit(@PathVariable String id, @RequestBody String body,
      @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
    var actor = sessions.require(request);
    allowed(actor.is("USER"));
    Map<String, Object> input;
    try {
      input = REVIEW_INPUT.readValue(body);
    } catch (JacksonException invalidJson) {
      throw new IllegalArgumentException("Invalid review JSON", invalidJson);
    }
    if (input == null || !input.keySet().equals(Set.of("stars", "comment"))
        || !(input.get("stars") instanceof Number)
        || !(input.get("comment") instanceof String text)) {
      throw new BusinessException("INVALID_INPUT", "请提供评分和评价内容", 400);
    }
    long stars = n(input, "stars");
    String comment = trimWebInput(text);
    if (stars < 1 || stars > 5 || comment.isEmpty() || comment.codePointCount(0, comment.length()) > 500) {
      throw new BusinessException("INVALID_INPUT", "评分需为1至5的整数，评价需为1至500个字符", 400);
    }
    return idem.execute(actor, "review:" + id, key, input,
        () -> reviews.submit(actor, id, (int) stars, comment));
  }
}
