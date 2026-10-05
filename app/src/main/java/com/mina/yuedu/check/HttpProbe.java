package com.mina.yuedu.check;

import com.mina.yuedu.network.SystemProxy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public final class HttpProbe {
  public static final class Response {
    public final int code;
    public final String body;
    public final String finalUrl;
    public final long millis;
    public Response(int code, String body, String finalUrl, long millis) {
      this.code = code; this.body = body; this.finalUrl = finalUrl; this.millis = millis;
    }
    /** HTTP 层可用：200-399 且 body 非空且非 Cloudflare 挑战页。 */
    public boolean httpOk() { return HttpProbe.httpOk(code, body); }
    public boolean hasBody() { return body != null && !body.trim().isEmpty(); }
  }

  private static final int MAX = 2 * 1024 * 1024;
  private static final Set<HttpURLConnection> ACTIVE = Collections.synchronizedSet(new HashSet<>());

  public static boolean httpOk(int code, String body) {
    if (code < 200 || code >= 400) return false;
    if (body == null || body.trim().isEmpty()) return false;
    return !isCloudflareChallenge(body);
  }

  public static boolean isCloudflareChallenge(String body) {
    if (body == null) return false;
    String low = body.toLowerCase(Locale.ROOT);
    return low.contains("just a moment") && low.contains("cloudflare");
  }

  public static void cancelAll() {
    synchronized (ACTIVE) {
      for (HttpURLConnection connection : new ArrayList<>(ACTIVE)) connection.disconnect();
      ACTIVE.clear();
    }
  }

  public static Response fetch(AnalyzeUrlLite req, Map<String, String> sourceHeaders, int timeoutMs) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
    Exception directErr;
    try {
      // 先直连：国内/无需代理的源直连最快
      return doFetch(req, sourceHeaders, timeoutMs, null);
    } catch (Exception e) {
      if (Thread.currentThread().isInterrupted()) throw e;
      directErr = e;
    }
    // 直连失败后自动走系统代理（梯子/手动代理）：被墙源经代理可通，无需用户判断开不开代理
    Proxy sys = SystemProxy.get();
    if (sys == null) throw directErr;
    try {
      return doFetch(req, sourceHeaders, timeoutMs, sys);
    } catch (Exception e2) {
      throw directErr; // 报告直连时的原始错误（更接近根因）
    }
  }

  /** 使用指定代理发起请求（用于校验内容级回退：直连拿到内容不对时，用系统代理重试）。 */
  public static Response fetch(AnalyzeUrlLite req, Map<String, String> sourceHeaders, int timeoutMs, Proxy proxy) throws Exception {
    return doFetch(req, sourceHeaders, timeoutMs, proxy);
  }

  private static Response doFetch(AnalyzeUrlLite req, Map<String, String> sourceHeaders, int timeoutMs, Proxy proxy) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
    long start = System.currentTimeMillis();
    String url = req.url;
    int hops = 0;
    while (true) {
      HttpURLConnection c = proxy == null
          ? (HttpURLConnection) new URL(url).openConnection()
          : (HttpURLConnection) new URL(url).openConnection(proxy);
      ACTIVE.add(c);
      if (Thread.currentThread().isInterrupted()) {
        ACTIVE.remove(c);
        c.disconnect();
        throw new InterruptedIOException("cancelled");
      }
      try {
        c.setConnectTimeout(Math.min(timeoutMs, 15000));
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        String method = req.method == null ? "GET" : req.method.toUpperCase(Locale.ROOT);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) YueduSourceDedupe/3.0-check");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (sourceHeaders != null) {
          for (Map.Entry<String, String> e : sourceHeaders.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
          }
        }
        if (req.headers != null) {
          for (Map.Entry<String, String> e : req.headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
          }
        }
        if ("POST".equals(method) && req.body != null) {
          c.setDoOutput(true);
          if (c.getRequestProperty("Content-Type") == null) {
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
          }
          if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
          c.getOutputStream().write(req.body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        // JDK 不跟随跨协议重定向（http→https），只会拿到 301 短页导致误判"搜索失效"；
        // 见到 3xx 带 Location 就手动再跳一次（最多 5 跳）
        if (code >= 300 && code < 400 && hops < 5) {
          String loc = c.getHeaderField("Location");
          if (loc != null && !loc.trim().isEmpty()) {
            url = new URL(new URL(url), loc.trim()).toString();
            hops++;
            continue; // finally 会断开旧连接
          }
        }
        InputStream raw = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (raw == null) {
          // 4xx/5xx 且服务器没给错误体时不能抛异常：状态码（含自定义可用码）要交给调用方判定
          if (code >= 400) {
            return new Response(code, "", url, System.currentTimeMillis() - start);
          }
          raw = c.getInputStream();
        }
        String enc = c.getContentEncoding();
        // 仅当 Content-Encoding 声明 gzip 且内容确实是 gzip 魔数时才解压，
        // 防止 Android 透明解压后二次解压导致误判。
        InputStream in = (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip"))
            ? wrapMaybeGzip(raw) : raw;
        String body = "";
        if (in != null) {
          try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n; int total = 0;
            while ((n = in.read(buf)) >= 0) {
              if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("cancelled");
              total += n;
              if (total > MAX) break;
              out.write(buf, 0, n);
            }
            body = out.toString(StandardCharsets.UTF_8.name());
          } finally {
            in.close();
          }
        }
        return new Response(code, body, url, System.currentTimeMillis() - start);
      } finally {
        ACTIVE.remove(c);
        c.disconnect();
      }
    }
  }

  private static InputStream wrapMaybeGzip(InputStream raw) throws IOException {
    // 仅当确实是 gzip 魔数时再包一层，防止已透明解压后再次解压失败
    PushbackInputStream pb = new PushbackInputStream(raw, 2);
    int b1 = pb.read(); int b2 = pb.read();
    if (b1 < 0) return pb;
    if (b2 < 0) { pb.unread(b1); return pb; }
    pb.unread(new byte[]{(byte) b1, (byte) b2});
    if (b1 == 0x1f && b2 == 0x8b) return new GZIPInputStream(pb);
    return pb;
  }

  public static Map<String, String> parseSourceHeader(String headerField) {
    LinkedHashMap<String, String> map = new LinkedHashMap<>();
    if (headerField == null || headerField.trim().isEmpty()) return map;
    String h = headerField.trim();
    if (h.startsWith("{") && h.endsWith("}")) {
      int i = 0;
      while (i < h.length()) {
        int k1 = h.indexOf('"', i);
        if (k1 < 0) break;
        int k2 = h.indexOf('"', k1 + 1);
        if (k2 < 0) break;
        String key = h.substring(k1 + 1, k2);
        int colon = h.indexOf(':', k2 + 1);
        if (colon < 0) break;
        int v1 = h.indexOf('"', colon + 1);
        if (v1 < 0) { i = colon + 1; continue; }
        int v2 = v1 + 1;
        StringBuilder sb = new StringBuilder();
        while (v2 < h.length()) {
          char ch = h.charAt(v2++);
          if (ch == '\\' && v2 < h.length()) { sb.append(h.charAt(v2++)); continue; }
          if (ch == '"') break;
          sb.append(ch);
        }
        map.put(key, sb.toString());
        i = v2;
      }
      return map;
    }
    String[] lines = h.split("\\n");
    for (String line : lines) {
      int c = line.indexOf(':');
      if (c > 0) map.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
    }
    return map;
  }
}
