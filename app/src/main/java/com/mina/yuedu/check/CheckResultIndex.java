package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验结果证据索引（零误串统一口径）。
 *
 * 一条 CheckSourceResult 只属于"可证明是同一份源"的记录：
 *   1. SourceRecord 实例身份（IdentityHashMap）——同一对象，结果确凿属于它；
 *   2. 完整内容指纹 URL+名称+raw Map 递归深等且唯一——去重/合并换实例后才兜底，
 *      同键多条结果（多义）直接剔除不猜。
 * 绝不按裸 URL 匹配：同 URL 规则/字段不同的源不互相套用校验证据；无证据返回 null。
 */
public final class CheckResultIndex {
  private final Map<SourceRecord, CheckSourceResult> byRef;
  private final Map<ContentKey, CheckSourceResult> byFingerprint;

  private CheckResultIndex(Map<SourceRecord, CheckSourceResult> byRef,
                           Map<ContentKey, CheckSourceResult> byFingerprint) {
    this.byRef = byRef;
    this.byFingerprint = byFingerprint;
  }

  public static CheckResultIndex of(List<CheckSourceResult> checks) {
    Map<SourceRecord, CheckSourceResult> ref = new IdentityHashMap<SourceRecord, CheckSourceResult>();
    Map<ContentKey, CheckSourceResult> fp = new HashMap<ContentKey, CheckSourceResult>();
    Set<ContentKey> ambiguous = new LinkedHashSet<ContentKey>();
    if (checks != null) {
      for (CheckSourceResult r : checks) {
        if (r == null || r.source == null) continue;
        ref.put(r.source, r);
        ContentKey key = ContentKey.of(r.source);
        if (key == null) continue;
        if (ambiguous.contains(key)) continue;
        if (fp.containsKey(key)) {
          fp.remove(key);
          ambiguous.add(key);
        } else {
          fp.put(key, r);
        }
      }
    }
    return new CheckResultIndex(ref, fp);
  }

  /** 实例匹配优先；指纹唯一兜底。无证据返回 null，绝不按 URL 猜。 */
  public CheckSourceResult find(SourceRecord source) {
    CheckSourceResult r = byRef.get(source);
    if (r != null) return r;
    ContentKey key = ContentKey.of(source);
    if (key == null) return null;
    return byFingerprint.get(key);
  }

  /** 完整源内容键：URL + 名称 + 整个 raw Map（Map.equals 递归深等嵌套 Map/List/标量）。 */
  private static final class ContentKey {
    final String url; final String name; final Map<String, Object> raw;
    private ContentKey(String u, String n, Map<String, Object> r) { url = u; name = n; raw = r; }
    static ContentKey of(SourceRecord s) {
      if (s == null || s.getUrl() == null) return null;
      return new ContentKey(s.getUrl(), s.getName(), s.getRaw());
    }
    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ContentKey)) return false;
      ContentKey x = (ContentKey) o;
      return eq(url, x.url) && eq(name, x.name) && raw.equals(x.raw);
    }
    @Override public int hashCode() {
      int h = url == null ? 0 : url.hashCode();
      h = 31 * h + (name == null ? 0 : name.hashCode());
      return 31 * h + raw.hashCode();
    }
    private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
  }
}
