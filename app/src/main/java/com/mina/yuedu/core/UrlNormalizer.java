package com.mina.yuedu.core;
import com.mina.yuedu.model.DedupeMode;
import java.net.*;
import java.util.*;
public final class UrlNormalizer {
  private static final Set<String> TRACK = new HashSet<>(Arrays.asList(
      "utm_source","utm_medium","utm_campaign","utm_term","utm_content","from","ref","spm","fbclid","gclid"));
  private UrlNormalizer(){}
  public static String completeKey(String raw) throws URISyntaxException { return canonical(raw, false, false); }
  public static String standardKey(String raw) throws URISyntaxException { return canonical(raw, false, true); }
  public static String hostKey(String raw) throws URISyntaxException { return canonical(raw, true, false); }
  public static String key(String raw, DedupeMode mode) throws URISyntaxException {
    if (mode == DedupeMode.AGGRESSIVE) return hostKey(raw);
    if (mode == DedupeMode.STANDARD) return standardKey(raw);
    return completeKey(raw);
  }

  /**
   * bookSourceUrl 中的 #后缀是阅读用来区分同站不同源的身份标签，
   * 并不是 HTTP 请求的一部分。校验网络时只使用井号前的传输地址。
   */
  public static String requestUrl(String raw) throws URISyntaxException {
    String[] parts = splitIdentity(raw);
    URI u = new URI(parts[0]);
    validate(raw, u);
    return parts[0];
  }

  private static String canonical(String raw, boolean hostOnly, boolean stripTrack) throws URISyntaxException {
    String[] parts = splitIdentity(raw);
    URI u = new URI(parts[0]);
    validate(raw, u);
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    String host = u.getHost().toLowerCase(Locale.ROOT);
    if (hostOnly) host = host.replaceFirst("^www\\.", "");
    int port = u.getPort();
    boolean def = (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
    String hp = host + (port >= 0 && !def ? ":" + port : "");
    if (hostOnly) return hp;
    String path = u.getRawPath();
    if (path == null || path.isEmpty()) path = "/";
    if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
    String q = u.getRawQuery();
    if (stripTrack && q != null && !q.isEmpty()) q = stripTracking(q);
    String key = scheme + "://" + hp + path + (q == null || q.isEmpty() ? "" : "?" + q);
    // 标准/严格模式保留非空 #标签，避免把 #简体、#🎃 等不同书源误合并。
    if (parts[1] != null && !parts[1].isEmpty()) key += "#" + parts[1];
    return key;
  }

  private static String[] splitIdentity(String raw) throws URISyntaxException {
    if (raw == null) throw new URISyntaxException("null", "missing URL");
    String s = raw.trim();
    if (s.isEmpty()) throw new URISyntaxException(raw, "empty URL");
    int hash = s.indexOf('#');
    String request = (hash < 0 ? s : s.substring(0, hash)).trim();
    String identity = hash < 0 ? null : s.substring(hash + 1).trim();
    if (request.isEmpty()) throw new URISyntaxException(raw, "missing request URL");
    return new String[]{request, identity};
  }

  private static void validate(String raw, URI u) throws URISyntaxException {
    if (u.getScheme() == null || u.getHost() == null) throw new URISyntaxException(raw, "missing scheme or host");
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) throw new URISyntaxException(raw, "unsupported scheme");
  }

  private static String stripTracking(String q) {
    List<String> kept = new ArrayList<>();
    for (String part : q.split("&")) {
      if (part.isEmpty()) continue;
      int i = part.indexOf('=');
      String k = i < 0 ? part : part.substring(0, i);
      try { k = URLDecoder.decode(k, "UTF-8"); } catch (Exception ignored) {}
      if (TRACK.contains(k.toLowerCase(Locale.ROOT))) continue;
      kept.add(part);
    }
    Collections.sort(kept);
    return String.join("&", kept);
  }
}
