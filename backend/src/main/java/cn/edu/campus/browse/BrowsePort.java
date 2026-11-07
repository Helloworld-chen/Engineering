package cn.edu.campus.browse;

import java.util.Map;
import cn.edu.campus.shared.Actor;

public interface BrowsePort {
  Map<String, Object> search(Actor viewer, String keyword, String category, int page, int pageSize);
}
