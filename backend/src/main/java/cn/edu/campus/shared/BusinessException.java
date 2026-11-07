package cn.edu.campus.shared;

public class BusinessException extends RuntimeException {
  public final String code;
  public final int status;

  public BusinessException(String code, String message, int status) {
    super(message);
    this.code = code;
    this.status = status;
  }

  public static void require(boolean condition, String code, String message) {
    if (!condition) throw new BusinessException(code, message, 409);
  }

  public static void allowed(boolean condition) {
    if (!condition) throw new BusinessException("FORBIDDEN", "无权执行此操作", 403);
  }
}
