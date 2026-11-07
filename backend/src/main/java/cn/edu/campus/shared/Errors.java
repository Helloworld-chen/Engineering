package cn.edu.campus.shared;

import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<?> business(BusinessException e) {
    return ResponseEntity.status(e.status).body(Map.of("code", e.code, "message", e.getMessage()));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentNotValidException.class,
    MaxUploadSizeExceededException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return ResponseEntity.badRequest()
        .body(Map.of("code", "INVALID_INPUT", "message", "输入格式不正确，请检查必填字段、文件大小和数据类型"));
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> conflict(Exception e) {
    return ResponseEntity.status(409)
        .body(Map.of("code", "CONFLICT", "message", "记录已存在或操作发生竞争，请刷新后重试"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(Exception e) {
    org.slf4j.LoggerFactory.getLogger(getClass()).error("Database operation rolled back", e);
    return ResponseEntity.status(503)
        .body(Map.of("code", "RETRYABLE", "message", "数据暂时无法保存，请使用原请求标识重试"));
  }
}
