package cn.edu.campus.files;
import cn.edu.campus.shared.*;
import cn.edu.campus.notification.TimePort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;
import static cn.edu.campus.shared.Rows.*;
import static cn.edu.campus.shared.BusinessException.*;
@Service
public class FileService {
 private final JdbcTemplate db;private final TimePort time;private final Path root;
 public FileService(JdbcTemplate db,TimePort time,@Value("${market.upload-dir}")String root){this.db=db;this.time=time;this.root=Path.of(root).toAbsolutePath().normalize();}
 public Map<String,Object> upload(Actor actor,String purpose,MultipartFile file) throws Exception {
  require(Set.of("PRODUCT","GOODS","PACKAGING","RETURN_GOODS","RETURN_PACKAGING").contains(purpose),"INVALID_PURPOSE","文件用途不正确");
  require(!file.isEmpty()&&file.getSize()<=5_000_000,"INVALID_FILE","图片不能为空且不能超过5 MB");
  String format;
  try(var stream=ImageIO.createImageInputStream(file.getInputStream())){
   var readers=ImageIO.getImageReaders(stream);require(readers.hasNext(),"INVALID_IMAGE","文件不是有效图片");var reader=readers.next();
   try{reader.setInput(stream);format=reader.getFormatName().toLowerCase(Locale.ROOT);require(Set.of("jpeg","jpg","png","webp").contains(format),"INVALID_IMAGE","仅支持JPEG、PNG、WebP");require((long)reader.getWidth(0)*reader.getHeight(0)<=20_000_000L,"IMAGE_TOO_LARGE","图片像素过大");require(reader.read(0)!=null,"INVALID_IMAGE","图片内容损坏");}finally{reader.dispose();}
  }
  String id=id(),storage=id+"."+(format.equals("jpg")?"jpeg":format),mime="image/"+(format.equals("jpg")?"jpeg":format);
  Files.createDirectories(root);Path target=root.resolve(storage);file.transferTo(target);
  try{db.update("INSERT INTO files(id,owner_id,purpose,mime,filename,storage_name,byte_size,created_at) VALUES(?,?,?,?,?,?,?,?)",id,actor.id(),purpose,mime,Objects.toString(file.getOriginalFilename(),"图片"),storage,file.getSize(),time.now());}catch(RuntimeException e){Files.deleteIfExists(target);throw e;}
  return map("id",id,"url","/api/v1/files/"+id,"purpose",purpose);
 }
 public void link(Actor actor,List<String> ids,String purpose,String type,String target){
  for(String id:new TreeSet<>(ids)){
   var rows=db.queryForList("SELECT * FROM files WHERE id=? FOR UPDATE",id);require(!rows.isEmpty(),"FILE_MISSING","图片不存在");var f=rows.getFirst();
   allowed(actor.id().equals(s(f,"owner_id")));require(purpose.equals(s(f,"purpose")),"FILE_PURPOSE","图片用途不匹配");
   require(f.get("linked_id")==null||(target.equals(s(f,"linked_id"))&&type.equals(s(f,"linked_type"))),"FILE_LINKED","图片已经关联其他业务");
   db.update("UPDATE files SET linked_type=?,linked_id=? WHERE id=?",type,target,id);
  }
 }
 public Map<String,Object> metadata(String id,Actor actor){
  var rows=db.queryForList("SELECT * FROM files WHERE id=?",id);if(rows.isEmpty())throw new BusinessException("NOT_FOUND","图片不存在",404);var f=rows.getFirst();
  boolean access=actor!=null&&actor.id().equals(s(f,"owner_id"));
  if("PRODUCT".equals(s(f,"linked_type"))){var p=db.queryForList("SELECT * FROM products WHERE id=?",f.get("linked_id"));access|=!p.isEmpty()&&("APPROVED".equals(s(p.getFirst(),"audit_status"))||actor!=null&&actor.is("ADMIN"));}
  if("ORDER".equals(s(f,"linked_type"))&&actor!=null){access|=db.queryForObject("SELECT COUNT(*) FROM orders WHERE id=? AND (buyer_id=? OR seller_id=?)",Integer.class,f.get("linked_id"),actor.id(),actor.id())>0;access|=actor.is("SUPPORT")&&db.queryForObject("SELECT COUNT(*) FROM cases WHERE order_id=?",Integer.class,f.get("linked_id"))>0;}
  allowed(access);return f;
 }
 public Path path(Map<String,Object> f){return root.resolve(s(f,"storage_name"));}
 @Scheduled(fixedDelay=3600000) public void cleanup(){for(var f:db.queryForList("SELECT * FROM files WHERE linked_id IS NULL AND created_at<?",time.now()-86400000L)){try{if(db.update("DELETE FROM files WHERE id=? AND linked_id IS NULL",f.get("id"))==1)Files.deleteIfExists(path(f));}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Temporary image cleanup failed: {}",f.get("id"));}}}
}
