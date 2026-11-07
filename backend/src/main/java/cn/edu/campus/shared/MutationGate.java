package cn.edu.campus.shared;
import org.springframework.stereotype.Component;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
/** Single-process demo lifecycle guard. Normal transactions remain concurrent. */
@Component
public class MutationGate {
 private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true);
 public void enter(){lock.readLock().lock();}
 public void leave(){lock.readLock().unlock();}
 public <T>T exclusive(Supplier<T> action){lock.writeLock().lock();try{return action.get();}finally{lock.writeLock().unlock();}}
}
