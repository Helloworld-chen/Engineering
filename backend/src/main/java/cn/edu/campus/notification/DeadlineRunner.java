package cn.edu.campus.notification;
import cn.edu.campus.trade.TradeTaskPort;
import cn.edu.campus.shared.MutationGate;
import cn.edu.campus.aftersales.AftersalesPort;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Set;
import static cn.edu.campus.shared.Rows.*;
@Component @Order(10) @ConditionalOnProperty(name="market.providers.notification",havingValue="demo")
public class DeadlineRunner implements ApplicationRunner, TaskRuntimePort {
 private final JdbcTemplate db;private final TimePort time;private final TradeTaskPort trade;private final AftersalesPort cases;private final TransactionTemplate tx;private final boolean scheduling;
 private volatile boolean ready;private final MutationGate gate;
 public DeadlineRunner(JdbcTemplate db,TimePort t,TradeTaskPort trade,AftersalesPort cases,PlatformTransactionManager manager,@Value("${market.scheduling:true}")boolean scheduling,MutationGate gate){this.gate=gate;this.db=db;time=t;this.trade=trade;this.cases=cases;tx=new TransactionTemplate(manager);this.scheduling=scheduling;}
 public boolean ready(){return ready;}
 public void run(ApplicationArguments args){recover();}
 public void recover(){gate.enter();try{ready=false;drain();ready=true;}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Deadline recovery failed; mutations remain gated",e);}finally{gate.leave();}}
 @Scheduled(fixedDelay=250) public void tick(){if(scheduling){if(!ready){recover();return;}try{drain();}catch(RuntimeException e){ready=false;org.slf4j.LoggerFactory.getLogger(getClass()).error("Deadline processing failed",e);}}}
 public void drain(){gate.enter();try{drainLocked();}finally{gate.leave();}}
 private synchronized void drainLocked(){
  for(;;){var due=db.queryForList("SELECT * FROM deadlines WHERE status='PENDING' AND due_at<=? ORDER BY due_at,id LIMIT 1",time.now());if(due.isEmpty())return;var task=due.getFirst();
   tx.executeWithoutResult(s->{
    // Do not hold a task row lock while waiting for product/order/case locks.
    String kind=s(task,"kind");if(Set.of("PAYMENT","DELIVERY","RETURN_DELIVERY","RECEIPT").contains(kind))trade.deadline(s(task,"aggregate_id"),kind,n(task,"generation"),n(task,"due_at"));else if(Set.of("REFUND_REVIEW","RETURN_INSPECTION","APPEAL").contains(kind))cases.deadline(s(task,"aggregate_id"),kind,n(task,"generation"),n(task,"due_at"));else throw new IllegalArgumentException("Unsupported task kind: "+kind);
    db.update("UPDATE deadlines SET status='DONE' WHERE id=?",task.get("id"));
   });
  }
 }
}
