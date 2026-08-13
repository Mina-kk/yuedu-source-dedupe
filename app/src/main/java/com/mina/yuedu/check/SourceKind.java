package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public enum SourceKind {
  NOVEL("小说"),
  COMIC("漫画"),
  VIDEO("视频"),
  AUDIO("音频"),
  FILE("文件");

  public final String label;
  SourceKind(String label) { this.label = label; }

  public String contentFailGroup() {
    switch (this) {
      case COMIC: return "图片失效";
      case VIDEO: return "播放失效";
      case AUDIO: return "音频失效";
      case FILE: return "下载失效";
      default: return "正文失效";
    }
  }

  public static Map<SourceKind, Integer> counts(List<SourceRecord> records) {
    Map<SourceKind, Integer> m = new LinkedHashMap<SourceKind, Integer>();
    for (SourceKind k : values()) m.put(k, 0);
    if (records == null) return m;
    for (SourceRecord s : records) {
      SourceKind k = of(s);
      m.put(k, m.get(k) + 1);
    }
    return m;
  }

  public static List<SourceRecord> filter(List<SourceRecord> records, Set<SourceKind> allow) {
    List<SourceRecord> out = new ArrayList<SourceRecord>();
    if (records == null || allow == null || allow.isEmpty()) return out;
    for (SourceRecord s : records) {
      if (allow.contains(of(s))) out.add(s);
    }
    return out;
  }

  public static SourceKind of(SourceRecord source) {
    if (source == null) return NOVEL;
    SourceKind fromField = fromTypeField(source.getRaw().get("bookSourceType"));
    if (fromField != null) return fromField;
    SourceKind fromGroup = fromHint(source.rawString("bookSourceGroup"));
    if (fromGroup != null) return fromGroup;
    SourceKind fromName = fromHint(source.getName());
    if (fromName != null) return fromName;
    return NOVEL;
  }

  static SourceKind fromTypeField(Object raw) {
    if (raw == null) return null;
    if (raw instanceof Number) return fromInt(((Number) raw).intValue());
    String s = String.valueOf(raw).trim();
    if (s.isEmpty()) return null;
    try { return fromInt(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
    return fromHint(s);
  }

  private static SourceKind fromInt(int v) {
    if (v == 1) return AUDIO;
    if (v == 2) return COMIC;
    if (v == 3) return FILE;
    if (v == 4) return VIDEO;
    return null;
  }

  static SourceKind fromHint(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    String low = s.toLowerCase(Locale.ROOT);
    if (containsAny(low, s, "视频", "影视", "短剧", "电影", "video")) return VIDEO;
    if (containsAny(low, s, "漫画", "动漫", "comic", "image", "图片")) return COMIC;
    if (containsAny(low, s, "听书", "有声", "音频", "audio", "music")) return AUDIO;
    if (containsAny(low, s, "文件", "file")) return FILE;
    if (containsAny(low, s, "小说", "文本", "novel")) return NOVEL;
    return null;
  }

  private static boolean containsAny(String low, String original, String... keys) {
    for (String k : keys) {
      if (k.equals(k.toLowerCase(Locale.ROOT))) {
        if (low.contains(k)) return true;
      } else if (original.contains(k) || low.contains(k.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
