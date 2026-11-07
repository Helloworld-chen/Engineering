package cn.edu.campus.notification;
public interface TimePort {
 long now();
 void schedule(String aggregateId,String kind,long generation,long dueAt);
 void emit(String recipient,String aggregateId,String type,String message);
}
