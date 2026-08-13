package com.mina.yuedu.check;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResultInspect {
  public static final class Hit {
    public final boolean ok;
    public final String reason;
    public final List<String> bookUrls;
    public Hit(boolean ok, String reason, List<String> bookUrls) {
      this.ok = ok; this.reason = reason; this.bookUrls = bookUrls;
    }
  }

  private static final Pattern HREF = Pattern.compile(
      "(?i)href\\s*=\\s*['\"]([^'\"]+)['\"]");
  private static final Pattern JSON_URL = Pattern.compile(
      "(?i)\"(?:bookUrl|infoUrl|url|tocUrl|chapterUrl)\"\\s*:\\s*\"([^\"]+)\"");

  public static Hit inspectListPage(String body, String baseUrl) {
    List<String> urls = new ArrayList<>();
    if (body == null || body.trim().isEmpty()) return new Hit(false, "empty body", urls);
    String t = body.trim();
    String low = t.toLowerCase(Locale.ROOT);
    if (low.contains("just a moment") && low.contains("cloudflare")) {
      return new Hit(false, "cloudflare", urls);
    }
    if (t.startsWith("{") || t.startsWith("[")) {
      Matcher m = JSON_URL.matcher(t);
      while (m.find() && urls.size() < 8) {
        String u = abs(m.group(1), baseUrl);
        if (u != null && !urls.contains(u)) urls.add(u);
      }
      boolean arrayish = t.startsWith("[") || low.contains("\"list\"") || low.contains("\"data\"") || low.contains("\"books\"");
      boolean hasName = low.contains("bookname") || low.contains("\"name\"") || low.contains("book_name");
      if (!urls.isEmpty() || (arrayish && t.length() > 20) || hasName) {
        return new Hit(true, "json list", urls);
      }
      return new Hit(t.length() > 40, "json opaque", urls);
    }
    Matcher m = HREF.matcher(t);
    while (m.find() && urls.size() < 12) {
      String href = m.group(1);
      if (href.startsWith("javascript:") || href.startsWith("#")) continue;
      if (href.contains("login") || href.endsWith(".css") || href.endsWith(".js")) continue;
      String u = abs(href, baseUrl);
      if (u != null && !urls.contains(u)) urls.add(u);
    }
    boolean meaningful = t.length() > 200 || !urls.isEmpty();
    if (low.contains("<html") && low.contains("404") && t.length() < 800) {
      return new Hit(false, "html 404", urls);
    }
    return new Hit(meaningful, meaningful ? "html list" : "too short", urls);
  }

  public static Hit inspectBookPage(String body) {
    if (body == null || body.trim().isEmpty()) return new Hit(false, "empty", new ArrayList<>());
    String t = body.trim();
    if (t.length() < 80) return new Hit(false, "too short", new ArrayList<>());
    String low = t.toLowerCase(Locale.ROOT);
    if (low.contains("just a moment") && low.contains("cloudflare")) {
      return new Hit(false, "cloudflare", new ArrayList<>());
    }
    List<String> urls = new ArrayList<>();
    Matcher m = JSON_URL.matcher(t);
    while (m.find() && urls.size() < 8) urls.add(m.group(1));
    return new Hit(true, "book page", urls);
  }

  public static Hit inspectContent(String body, SourceKind kind) {
    if (kind == SourceKind.COMIC) return inspectMedia(body, "jpg", "jpeg", "png", "webp", "gif", "bmp");
    if (kind == SourceKind.VIDEO) return inspectMedia(body, "m3u8", "mp4", "flv", "m3u", "mkv", "ts");
    if (kind == SourceKind.AUDIO) return inspectMedia(body, "mp3", "m4a", "flac", "aac", "ogg");
    if (kind == SourceKind.FILE) return inspectMedia(body, "zip", "txt", "epub", "pdf", "rar");
    return inspectChapterContent(body);
  }

  public static Hit inspectChapterContent(String body) {
    if (body == null || body.trim().isEmpty()) return new Hit(false, "empty", new ArrayList<>());
    String t = body.trim();
    String text = t.replaceAll("(?is)<script[\\s\\S]*?</script>", " ")
        .replaceAll("(?is)<style[\\s\\S]*?</style>", " ")
        .replaceAll("(?is)<[^>]+>", " ")
        .replaceAll("\\s+", " ")
        .trim();
    if (text.length() < 30) return new Hit(false, "content too short", new ArrayList<>());
    return new Hit(true, "content", new ArrayList<>());
  }

  private static Hit inspectMedia(String body, String... exts) {
    if (body == null || body.trim().isEmpty()) return new Hit(false, "empty", new ArrayList<>());
    String t = body.trim();
    String low = t.toLowerCase(Locale.ROOT);
    if (low.contains("just a moment") && low.contains("cloudflare")) {
      return new Hit(false, "cloudflare", new ArrayList<>());
    }
    for (String ext : exts) {
      if (low.contains("." + ext)) return new Hit(true, "media " + ext, new ArrayList<>());
    }
    if (JSON_URL.matcher(t).find()) return new Hit(true, "media url field", new ArrayList<>());
    return new Hit(false, "no media", new ArrayList<>());
  }

  private static String abs(String url, String base) {
    if (url == null || url.isEmpty()) return null;
    try {
      if (url.startsWith("http://") || url.startsWith("https://")) return url;
      return new URL(new URL(base), url).toString();
    } catch (Exception e) {
      return null;
    }
  }

  public static boolean hasRule(Object rule) {
    if (rule == null) return false;
    if (rule instanceof Map) return !((Map<?, ?>) rule).isEmpty();
    return !String.valueOf(rule).trim().isEmpty();
  }

  public static String ruleString(Map<String, Object> rule, String key) {
    if (rule == null) return null;
    Object v = rule.get(key);
    return v == null ? null : String.valueOf(v);
  }
}
