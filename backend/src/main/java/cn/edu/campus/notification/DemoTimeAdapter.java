package cn.edu.campus.notification;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import static cn.edu.campus.shared.Rows.*;
@Service @ConditionalOnProperty(name="market.providers.notification",havingValue="demo")
public class DemoTimeAdapter implements TimePort {
 private final JdbcTemplate db;public DemoTimeAdapter(JdbcTemplate db){this.db=db;}
 public long now(){var r=db.queryForMap("SELECT * FROM clock_state WHERE id=1");return r.get("frozen_at")==null?System.currentTimeMillis()+n(r,"offset_millis"):n(r,"frozen_at");}
 public void schedule(String id,String kind,long generation,long due){db.update("INSERT IGNORE INTO deadlines(aggregate_id,kind,generation,due_at) VALUES(?,?,?,?)",id,kind,generation,due);}
 public void emit(String recipient,String aggregate,String type,String message){db.update("INSERT INTO outbox(recipient_id,aggregate_id,event_type,message,created_at) VALUES(?,?,?,?,?)",recipient,aggregate,type,message,now());}
}
