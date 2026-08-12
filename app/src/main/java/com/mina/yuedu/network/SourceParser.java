package com.mina.yuedu.network;
import com.mina.yuedu.core.MiniJson;
import com.mina.yuedu.model.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
public final class SourceParser {
  public static final class ParseResult {
    private final List<SourceRecord> records; private final List<InvalidSource> invalid;
    ParseResult(List<SourceRecord> r, List<InvalidSource> i){ records=r; invalid=i; }
    public List<SourceRecord> getRecords(){ return records; } public List<InvalidSource> getInvalid(){ return invalid; }
  }
  private SourceParser(){}
  public static ParseResult parseArray(String json, int start){
    List<SourceRecord> out=new ArrayList<>(); List<InvalidSource> bad=new ArrayList<>();
    Object root;
    try{ root=MiniJson.parse(json); }catch(Exception e){ bad.add(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, e.getMessage())); return new ParseResult(out,bad); }
    List<?> list;
    if(root instanceof List) list=(List<?>)root;
    else if(root instanceof Map){
      Map<?,?> m=(Map<?,?>)root;
      Object data=m.get("data"); if(!(data instanceof List)) data=m.get("list");
      if(!(data instanceof List)) data=m.get("sources");
      if(data instanceof List) list=(List<?>)data;
      else if(m.containsKey("bookSourceUrl") || m.containsKey("bookSourceName")) list=Collections.singletonList(m);
      else { bad.add(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, "root is not array")); return new ParseResult(out,bad); }
    } else { bad.add(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, "root is not array")); return new ParseResult(out,bad); }
    int i=0;
    for(Object x: list){
      if(!(x instanceof Map)){ bad.add(new InvalidSource(InvalidSource.Kind.NOT_OBJECT, "index "+i)); i++; continue; }
      @SuppressWarnings("unchecked") Map<String,Object> m=new LinkedHashMap<>((Map<String,Object>)x);
      Object n=m.get("bookSourceName"), u=m.get("bookSourceUrl");
      out.add(new SourceRecord(start+i, n==null?"":String.valueOf(n), u==null?null:String.valueOf(u), m)); i++;
    }
    return new ParseResult(out,bad);
  }
  public static String extractIndirectUrl(String body){
    if(body==null) return null;
    String t=body.trim();
    if(!(t.startsWith("{") || t.startsWith("["))) return null;
    try{
      Object root=MiniJson.parse(t);
      if(root instanceof Map){
        @SuppressWarnings("unchecked") Map<String,Object> m=(Map<String,Object>)root;
        for(String k: new String[]{"msg","downloadUrl","url","link","path","data"}){
          Object v=m.get(k);
          if(v instanceof String && String.valueOf(v).matches("https?://.+")) return String.valueOf(v).trim();
          if(v instanceof Map){
            @SuppressWarnings("unchecked") Map<String,Object> mm=(Map<String,Object>)v;
            for(String k2: new String[]{"url","downloadUrl","link","path"}){
              Object vv=mm.get(k2);
              if(vv instanceof String && String.valueOf(vv).matches("https?://.+")) return String.valueOf(vv).trim();
            }
          }
        }
      }
    }catch(Exception ignored){}
    return null;
  }
  public static List<String> discoverYckJsonUrls(String html, String base){
    LinkedHashSet<String> set=new LinkedHashSet<>();
    if(html==null) return new ArrayList<>();
    String text=html.replace("&amp;","&");
    Pattern p=Pattern.compile("(?:href|value|data-url)\\s*=\\s*['\"]([^'\"]+?\\.json(?:\\?[^'\"]*)?)['\"]", Pattern.CASE_INSENSITIVE);
    Matcher m=p.matcher(text);
    while(m.find()) add(set, m.group(1), base);
    Pattern raw=Pattern.compile("https?://[^\\s'\"<>]+/yuedu/(?:shuyuan|shuyuans|rss|rsss)/json/[^\\s'\"<>]+", Pattern.CASE_INSENSITIVE);
    m=raw.matcher(text); while(m.find()) add(set, m.group(), base);
    Pattern d=Pattern.compile("(?:href|value|data-url)\\s*=\\s*['\"](https?://[^'\"]+/d/[^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    m=d.matcher(text); while(m.find()) addAny(set, m.group(1), base);
    return new ArrayList<>(set);
  }
  private static void add(Set<String> s, String raw, String base){
    try{ URL u=new URL(new URL(base), raw); String path=u.getPath();
      if(path.matches("(?i).*/yuedu/(shuyuan|shuyuans|rss|rsss)/json/.*\\.json")) s.add(u.toString());
      else if(path.toLowerCase(Locale.ROOT).endsWith(".json")) s.add(u.toString());
    }catch(Exception ignored){}
  }
  private static void addAny(Set<String> s, String raw, String base){
    try{ s.add(new URL(new URL(base), raw).toString()); }catch(Exception ignored){}
  }
}
