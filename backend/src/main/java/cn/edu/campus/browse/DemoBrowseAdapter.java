package cn.edu.campus.browse;
import cn.edu.campus.notification.TimePort;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
@Service @ConditionalOnProperty(name="market.providers.browse",havingValue="demo")
public class DemoBrowseAdapter implements BrowsePort {
 private final JdbcTemplate db;private final TimePort clock;
 public DemoBrowseAdapter(JdbcTemplate db,TimePort clock){this.db=db;this.clock=clock;}
 public Map<String,Object> search(String keyword,String category,int page,int size){
  page=Math.max(1,page);size=Math.max(1,Math.min(100,size));String where=" WHERE audit_status='APPROVED' AND sale_status IN ('AVAILABLE','LOCKED') AND title LIKE ? AND (?='' OR category=?)";
  var list=db.queryForList("SELECT * FROM products"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?","%"+keyword+"%",category,category,size,(long)(page-1)*size).stream().map(r->{var v=view(r);v.put("images",strings(r.get("images")));return v;}).toList();
  return map("items",list,"total",db.queryForObject("SELECT COUNT(*) FROM products"+where,Long.class,"%"+keyword+"%",category,category),"page",page,"pageSize",size,"serverTime",instant(clock.now()));
 }
}
