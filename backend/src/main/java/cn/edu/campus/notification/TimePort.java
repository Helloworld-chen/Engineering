package cn.edu.campus.notification;

public interface TimePort {
  String BUSINESS_CHANGED = "BUSINESS_CHANGED";

  long now();

  void schedule(String aggregateId, String kind, long generation, long dueAt);

  void emit(String recipient, String aggregateId, String type, String message);
  /** Persist a business refresh without creating an inbox notification. */
  default void changed(String recipient, String aggregateId) {
    emit(recipient, aggregateId, BUSINESS_CHANGED, "");
  }
}
