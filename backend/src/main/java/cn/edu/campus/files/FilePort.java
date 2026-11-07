package cn.edu.campus.files;

import cn.edu.campus.shared.Actor;
import java.nio.file.Path;
import java.util.*;
import org.springframework.web.multipart.MultipartFile;

/** Shared evidence storage. link joins the caller transaction; private reads enforce access. */
public interface FilePort {
  Map<String, Object> upload(Actor actor, String purpose, MultipartFile file) throws Exception;

  void link(Actor actor, List<String> ids, String purpose, String type, String target);

  Map<String, Object> metadata(String id, Actor actor);

  Path path(Map<String, Object> metadata);
}
