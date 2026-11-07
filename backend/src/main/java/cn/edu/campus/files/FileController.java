package cn.edu.campus.files;
import cn.edu.campus.identity.Sessions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;
import static cn.edu.campus.shared.Rows.*;
@RestController @RequestMapping("/api/v1/files")
public class FileController {
 private final FileService files;private final Sessions sessions;
 public FileController(FileService f,Sessions s){files=f;sessions=s;}
 @PostMapping Map<String,Object> upload(@RequestParam String purpose,@RequestParam MultipartFile file,HttpServletRequest r)throws Exception{return files.upload(sessions.require(r),purpose,file);}
 @GetMapping("/{id}") ResponseEntity<?> get(@PathVariable String id,HttpServletRequest r){var f=files.metadata(id,sessions.optional(r));return ResponseEntity.ok().contentType(MediaType.parseMediaType(s(f,"mime"))).header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").body(new FileSystemResource(files.path(f)));}
}
