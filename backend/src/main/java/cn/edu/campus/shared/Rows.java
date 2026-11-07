package cn.edu.campus.shared;
import java.util.*;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
public final class Rows {
 private Rows(){}
 public static final JsonMapper JSON=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
 public static String id(){return UUID.randomUUID().toString();}
 public static String s(Map<String,Object> r,String key){return r.get(key)==null?"":r.get(key).toString();}
 public static long n(Map<String,Object> r,String key){if(r.get(key)==null)return 0;try{return new java.math.BigDecimal(r.get(key).toString()).longValueExact();}catch(RuntimeException e){throw new IllegalArgumentException("Expected integer field: "+key);}}
 public static boolean b(Map<String,Object> r,String key){Object v=r.get(key);return v instanceof Boolean?(boolean)v:v instanceof Number&&((Number)v).intValue()!=0;}
 public static Map<String,Object> map(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
 public static String json(Object v){return JSON.writeValueAsString(v);}
 @SuppressWarnings("unchecked") public static Map<String,Object> object(String v){return JSON.readValue(v,Map.class);}
 @SuppressWarnings("unchecked") public static List<String> strings(Object v){if(v==null)return List.of();if(v instanceof String s)return JSON.readValue(s,List.class);return (List<String>)v;}
 public static String instant(Object v){return v==null?null:new java.time.format.DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(((Number)v).longValue()));}
 public static Map<String,Object> view(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();row.forEach((k,v)->{String[] p=k.split("_");String key=p[0];for(int i=1;i<p.length;i++)key+=Character.toUpperCase(p[i].charAt(0))+p[i].substring(1);out.put(key,(k.endsWith("_at")||k.endsWith("_deadline")||k.equals("deadline"))?instant(v):v);});return out;}
 public static String text(Map<String,Object> input,String key,int min,int max){String v=s(input,key).trim();if(v.length()<min||v.length()>max)throw new BusinessException("INVALID_INPUT",key+"长度应为"+min+"至"+max+"个字符",400);return v;}
}
