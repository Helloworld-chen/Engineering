package cn.edu.campus.browse;
import java.util.Map;
public interface BrowsePort { Map<String,Object> search(String keyword,String category,int page,int pageSize); }
