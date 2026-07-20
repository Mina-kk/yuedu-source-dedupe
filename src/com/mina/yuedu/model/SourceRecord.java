package com.mina.yuedu.model;
import java.util.*;
public final class SourceRecord {
 private final int order; private final String name,url; private final Map<String,Object> raw;
 public SourceRecord(int order,String name,String url,Map<String,Object> raw){this.order=order;this.name=name;this.url=url;this.raw=Collections.unmodifiableMap(new LinkedHashMap<>(raw));}
 public int getOrder(){return order;} public String getName(){return name;} public String getUrl(){return url;} public Map<String,Object> getRaw(){return raw;}
 public SourceRecord withName(String n){Map<String,Object> m=new LinkedHashMap<>(raw);m.put("bookSourceName",n);return new SourceRecord(order,n,url,m);}
}
