package com.mina.yuedu.core;
import com.mina.yuedu.model.*; import java.util.*;
public final class DedupeEngine {
 private DedupeEngine(){}
 public static DedupeResult run(List<SourceRecord> sources,DedupeMode mode,boolean clean){Map<String,List<SourceRecord>> buckets=new LinkedHashMap<>();List<InvalidSource> invalid=new ArrayList<>();for(SourceRecord s:sources){String u=s.getUrl();if(u==null){invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL,s.getName()));continue;}if(u.trim().isEmpty()){invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL,s.getName()));continue;}try{String k=UrlNormalizer.key(u,mode);SourceRecord v=clean?s.withName(NameCleaner.clean(s.getName())):s;buckets.computeIfAbsent(k,x->new ArrayList<>()).add(v);}catch(Exception e){invalid.add(new InvalidSource(InvalidSource.Kind.INVALID_URL,u));}}
  List<SourceRecord> kept=new ArrayList<>();List<DuplicateGroup> groups=new ArrayList<>();for(Map.Entry<String,List<SourceRecord>> e:buckets.entrySet()){List<SourceRecord> g=e.getValue();g.sort((a,b)->{int d=Integer.compare(score(b),score(a));return d!=0?d:Integer.compare(a.getOrder(),b.getOrder());});SourceRecord k=g.get(0);kept.add(k);if(g.size()>1)groups.add(new DuplicateGroup(e.getKey(),reason(mode),k,g.subList(1,g.size())));}kept.sort(Comparator.comparingInt(SourceRecord::getOrder));return new DedupeResult(sources.size(),kept,groups,invalid);
 }
 private static int score(SourceRecord s){int n=0;if(s.getName()!=null&&!s.getName().trim().isEmpty())n+=10;if(s.getUrl()!=null&&!s.getUrl().trim().isEmpty())n+=10;for(Object v:s.getRaw().values())if(v!=null&&!String.valueOf(v).trim().isEmpty())n++;Object en=s.getRaw().get("enabled");if(!(en instanceof Boolean)||(Boolean)en)n+=5;return n;}
 private static String reason(DedupeMode m){return m==DedupeMode.AGGRESSIVE?"激进模式下域名相同":"规范化书源 URL 相同";}
}
