package cn.edu.campus.notification;

/** M08 must finish overdue recovery before allowing business mutations. */
public interface TaskRuntimePort {
  boolean ready();
}
