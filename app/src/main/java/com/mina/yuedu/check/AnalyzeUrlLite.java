package com.mina.yuedu.check;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AnalyzeUrlLite {
  public final String url;
  public final String method;
  public final String body;
  public final Map<String, String> headers;
  public final boolean usesJs;

  private AnalyzeUrlLite(String url, String method, String body, Map<String, String> headers, boolean usesJs) {
    this.url = url;
    this.method = method;
    this.body = body;
    this.headers = headers;
    this.usesJs = usesJs;
  }

  public static AnalyzeUrlLite parse(String raw, String baseUrl, String keyword, int page) {
    if (raw == null) raw = "";
    String s = raw.trim();
    String lowerAll = s.toLowerCase(Locale.ROOT);
    boolean usesJs = s.contains("<js>") || s.contains("</js>") || s.contains("@js:") || lowerAll.contains("javascript");
    String method = "GET";
    String body = null;
    Map<String, String> headers = new HashMap<>();

    String urlPart = s;
    String opt = null;
    int comma = s.lastIndexOf(",{");
    if (comma > 0) {
      urlPart = s.substring(0, comma).trim();
      opt = s.substring(comma + 1).trim();
    }

    String keyEnc;
    try {
      keyEnc = URLEncoder.encode(keyword == null ? "" : keyword, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      keyEnc = keyword == null ? "" : keyword;
    }
    String pageStr = String.valueOf(Math.max(1, page));
    urlPart = urlPart
        .replace("{{key}}", keyEnc)
        .replace("{{page}}", pageStr)
        .replace("{key}", keyEnc)
        .replace("{page}", pageStr)
        .replace("{{host}}", hostOf(baseUrl))
        .replace("{host}", hostOf(baseUrl));

    if (opt != null && opt.startsWith("{") && opt.endsWith("}")) {
      String lower = opt.toLowerCase(Locale.ROOT);
      if (lower.contains("method") && lower.contains("post")) method = "POST";
      String bodyVal = extractJsonString(opt, "body");
      if (bodyVal != null) {
        body = bodyVal
            .replace("{{key}}", keyEnc)
            .replace("{key}", keyEnc)
            .replace("{{page}}", pageStr)
            .replace("{page}", pageStr);
      }
      String ua = extractJsonString(opt, "User-Agent");
      if (ua == null) ua = extractJsonString(opt, "userAgent");
      if (ua != null) headers.put("User-Agent", ua);
      String cookie = extractJsonString(opt, "Cookie");
      if (cookie != null) headers.put("Cookie", cookie);
      String referer = extractJsonString(opt, "Referer");
      if (referer != null) headers.put("Referer", referer);
    }

    return new AnalyzeUrlLite(absolutize(urlPart, baseUrl), method, body, headers, usesJs);
  }

  private static String hostOf(String base) {
    try { return new URL(base).getHost(); } catch (Exception e) { return ""; }
  }

  private static String absolutize(String url, String base) {
    if (url == null || url.isEmpty()) return base;
    String u = url.trim();
    if (u.startsWith("http://") || u.startsWith("https://")) return u;
    try { return new URL(new URL(base), u).toString(); } catch (Exception e) { return u; }
  }

  private static String extractJsonString(String json, String key) {
    String dq = "\"" + key + "\"";
    String sq = "'" + key + "'";
    for (String p : new String[] {dq, sq}) {
      int i = json.indexOf(p);
      if (i < 0) continue;
      int colon = json.indexOf(':', i + p.length());
      if (colon < 0) continue;
      int j = colon + 1;
      while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
      if (j >= json.length()) continue;
      char q = json.charAt(j);
      if (q != '"' && q != '\'') continue;
      int k = j + 1;
      StringBuilder sb = new StringBuilder();
      while (k < json.length()) {
        char c = json.charAt(k++);
        if (c == '\\' && k < json.length()) { sb.append(json.charAt(k++)); continue; }
        if (c == q) return sb.toString();
        sb.append(c);
      }
    }
    return null;
  }
}
