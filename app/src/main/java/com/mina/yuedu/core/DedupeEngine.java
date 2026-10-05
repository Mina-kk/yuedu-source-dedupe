package com.mina.yuedu.core;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.*;
import java.util.*;
public final class DedupeEngine {
  private DedupeEngine(){}
  public static DedupeResult run(List<SourceRecord> sources, DedupeMode mode, boolean clean){
    List<InvalidSource> invalid=new ArrayList<>();
    // 名称模式：按名称相似度分组，不走 URL 规范化
    if (mode == DedupeMode.NAME) {
      return runByName(sources, clean, invalid);
    }
    Map<String,List<SourceRecord>> buckets=new LinkedHashMap<>();
    for(SourceRecord s: sources){
      String u=s.getUrl();
      if(u==null){ invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL, s.getName())); continue; }
      if(u.trim().isEmpty()){ invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL, s.getName())); continue; }
      try{
        String k=SourceKind.of(s).name()+"|"+UrlNormalizer.key(u, mode);
        SourceRecord v=clean? s.withName(NameCleaner.clean(s.getName())) : s;
        buckets.computeIfAbsent(k, x->new ArrayList<>()).add(v);
      }catch(Exception e){ invalid.add(new InvalidSource(InvalidSource.Kind.INVALID_URL, u)); }
    }
    DedupeResult result = buildResult(sources.size(), buckets, mode, invalid);
    int accounted = result.getRetained().size() + result.getDuplicateCount() + result.getInvalid().size();
    if (accounted != sources.size()) {
      throw new IllegalStateException("dedupe lost sources: in=" + sources.size() + " accounted=" + accounted);
    }
    return result;
  }

  /**
   * 按名称相似度去重（保守版）。
   *
   * 名称相似只用作"候选"粗筛——把疑似同站的源聚到一起。真正折叠（删源）需要
   * 同时满足全部可证明条件：
   *   1. SourceKind 相同（小说/漫画/… 同名但内容是两类，不合并）；
   *   2. URL 原文完全相同——不做任何规范化：尾斜杠、显式默认端口、大小写差异
   *      都视为不同身份，各自保留；非 HTTP(s) 或解析失败的 URL 不可证明身份，
   *      每条记录独占一个 opaque key，即使 URL/order/raw 全同也绝不合并；
   *   3. 完整 raw Map 结构相等（Map.equals 递归深等全部业务字段：规则、header、
   *      loginUi、enabled、comment 等任一字段不同即不折叠）。
   * 任一条件不满足即各自保留。"同名但 URL 细节或任何字段不同"的源永远不会被误删。
   */
  private static DedupeResult runByName(List<SourceRecord> sources, boolean clean, List<InvalidSource> invalid) {
    List<SourceRecord> processed = new ArrayList<>(sources.size());
    for (SourceRecord s : sources) {
      if (s.getUrl() == null) {
        invalid.add(new InvalidSource(InvalidSource.Kind.MISSING_URL, s.getName()));
        continue;
      }
      if (s.getUrl().trim().isEmpty()) {
        invalid.add(new InvalidSource(InvalidSource.Kind.EMPTY_URL, s.getName()));
        continue;
      }
      processed.add(clean ? s.withName(NameCleaner.clean(s.getName())) : s);
    }
    // 名称相似度分组：仅作候选集合，不直接删。
    List<List<SourceRecord>> nameGroups = NameSimilarity.groupByName(processed);
    Set<SourceRecord> grouped = new HashSet<>();
    Map<Object, List<SourceRecord>> buckets = new LinkedHashMap<>();
    for (List<SourceRecord> group : nameGroups) {
      String nameKey = NameSimilarity.normalize(group.get(0).getName());
      if (nameKey.isEmpty()) nameKey = group.get(0).getName();
      // 组内按"类型 + 原文URL + 完整raw"细分桶：三者结构全同才是真重复。
      Map<Object, List<SourceRecord>> sub = new LinkedHashMap<>();
      for (SourceRecord s : group) {
        sub.computeIfAbsent(identityBucketKey(s), x -> new ArrayList<>()).add(s);
        grouped.add(s);
      }
      for (Map.Entry<Object, List<SourceRecord>> e : sub.entrySet()) {
        // 桶 key = [名称key, 身份对象] 的 List：List.equals 深度比较，不依赖字符串拼接或hashCode，
        // 同名组内不同身份、不同名组同 hash 都不会撞桶误并。
        buckets.computeIfAbsent(
            java.util.Arrays.<Object>asList(nameKey, e.getKey()), x -> new ArrayList<>()).addAll(e.getValue());
      }
    }
    // 未被名称分组的源单独保留。key 用身份对象，不同身份绝不撞桶合并。
    for (SourceRecord s : processed) {
      if (!grouped.contains(s)) {
        buckets.computeIfAbsent(identityBucketKey(s), x -> new ArrayList<>()).add(s);
      }
    }
    DedupeResult result = buildResult(sources.size(), buckets, DedupeMode.NAME, invalid);
    // invariant：retained + 各组 removed + invalid 必须等于输入总数，否则有源被静默丢弃
    int accounted = result.getRetained().size() + result.getDuplicateCount() + result.getInvalid().size();
    if (accounted != sources.size()) {
      throw new IllegalStateException("NAME dedupe lost sources: in=" + sources.size()
          + " accounted=" + accounted);
    }
    return result;
  }

  /**
   * 完全身份分桶 key（对象版，字符串拼接会碰撞故不用）。
   * HTTP(s) 源：SourceKind + 原文URL + 完整 raw Map 深等 → 三者全同才同桶折叠。
   *   - URL 用原文（不 normalize）：尾斜杠、显式默认端口、大小写差异都不归并；
   *   - raw 深等覆盖 header/cookie/loginUi/credentials/enabled 等全部业务字段。
   * 非 HTTP(s) 源：URL 不可证明身份 → 每次调用返回全新的 opaque Object。
   *   key 即身份本身，两个源即使 URL/order/raw 全同也永不撞桶，绝不合并。
   */
  private static Object identityBucketKey(SourceRecord s) {
    if (!UrlNormalizer.isHttpUrl(s.getUrl())) {
      return new Object();
    }
    return new FullIdentityKey(SourceKind.of(s), s.getUrl(), s.getRaw());
  }

  /**
   * 完整身份 key：SourceKind + 原文URL + raw Map（Map.equals 递归深等嵌套 Map/List/标量）。
   * 仅当三者在结构上完全相等才同桶——这就是"可证明同一份源"。
   */
  private static final class FullIdentityKey {
    final SourceKind kind; final String url; final Map<String,Object> raw;
    FullIdentityKey(SourceKind k,String u,Map<String,Object> r){kind=k;url=u;raw=r;}
    @Override public boolean equals(Object o){
      if(this==o) return true;
      if(!(o instanceof FullIdentityKey)) return false;
      FullIdentityKey x=(FullIdentityKey)o;
      return kind==x.kind
          && (url==null? x.url==null : url.equals(x.url))
          && raw.equals(x.raw);
    }
    @Override public int hashCode(){
      int h=kind.hashCode(); h=31*h+(url==null?0:url.hashCode()); return 31*h+raw.hashCode();
    }
  }

  /** 从 buckets 构建最终结果（invalid 必须传入，否则无效源会凭空消失）。key 可为任意对象。 */
  private static DedupeResult buildResult(int total, Map<?, List<SourceRecord>> buckets,
                                          DedupeMode mode, List<InvalidSource> invalid) {
    List<SourceRecord> kept=new ArrayList<>(); List<DuplicateGroup> groups=new ArrayList<>();
    for(Map.Entry<?,List<SourceRecord>> e: buckets.entrySet()){
      List<SourceRecord> g=e.getValue();
      g.sort((a,b)->{ int d=Integer.compare(score(b), score(a)); return d!=0? d : Integer.compare(a.getOrder(), b.getOrder()); });
      SourceRecord k=g.get(0); kept.add(k);
      if(g.size()>1) groups.add(new DuplicateGroup(String.valueOf(e.getKey()), reason(mode), k, g.subList(1,g.size())));
    }
    kept.sort(Comparator.comparingInt(SourceRecord::getOrder));
    return new DedupeResult(total, kept, groups, invalid);
  }

  private static int score(SourceRecord s){
    int n=0;
    if(s.getName()!=null && !s.getName().trim().isEmpty()) n+=10;
    // 含"官方"等优先关键词加分
    if (NameSimilarity.hasPreferredKeyword(s.getName())) n+=15;
    if(s.getUrl()!=null && !s.getUrl().trim().isEmpty()) n+=10;
    for(Object v: s.getRaw().values()) if(v!=null && !String.valueOf(v).trim().isEmpty()) n++;
    Object en=s.getRaw().get("enabled");
    if(!(en instanceof Boolean) || (Boolean)en) n+=5;
    Object t=s.getRaw().get("lastUpdateTime");
    if(t instanceof Number) n += recencyBonus(((Number)t).longValue());
    else if(t!=null){ try{ n += recencyBonus(Long.parseLong(String.valueOf(t)));}catch(Exception ignored){} }
    for(String rk: new String[]{"ruleSearch","ruleBookInfo","ruleToc","ruleContent","ruleExplore"}){
      Object rv=s.getRaw().get(rk);
      if(rv instanceof Map && !((Map<?,?>)rv).isEmpty()) n+=8;
      else if(rv!=null && !String.valueOf(rv).trim().isEmpty()) n+=4;
    }
    // 名称简洁性加分：名称越短越清晰（通常更好）
    String name = s.getName();
    if (name != null && !name.trim().isEmpty()) {
      int len = name.trim().length();
      if (len <= 6) n += 5;      // 极简名称
      else if (len <= 10) n += 3; // 简洁名称
    }
    return n;
  }

  /** 新近度加分：最近更新 50 分起，每满 30 天衰减 1 分，下限 0。lastUpdateTime 是毫秒时间戳；
   *  旧公式除以 1e9 对任何真实时间戳都恒超过上限，等于只有"有无时间戳"之分。 */
  static int recencyBonus(long epochMs){
    long days = (System.currentTimeMillis() - epochMs) / 86_400_000L;
    if(days < 0) days = 0;
    return 50 - (int)Math.min(50, days / 30);
  }

  private static String reason(DedupeMode m){
    if(m==DedupeMode.AGGRESSIVE) return "同类型同域名相同";
    if(m==DedupeMode.STANDARD) return "标准化书源 URL 相同（已剥离跟踪参数）";
    if(m==DedupeMode.NAME) return "名称相似且同类型、URL原文与全部字段完全一致才折叠（URL细节/任一字段不同或不可证的源已各自保留）";
    return "规范化书源 URL 相同";
  }
}
