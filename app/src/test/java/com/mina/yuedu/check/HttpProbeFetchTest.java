package com.mina.yuedu.check;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 真实 HTTP 链路：裸 ServerSocket 回环验证 HttpProbe.fetch 的传输层行为。 */
public class HttpProbeFetchTest {

  /** 起一次性回环 HTTP 服务，按 status/headers/body 回应第一个请求。 */
  private static String serveOnce(int status, String[] headers, byte[] body) throws Exception {
    final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    final int port = ss.getLocalPort();
    Thread t = new Thread(() -> {
      try {
        Socket s = ss.accept();
        // 读请求头直到空行
        ByteArrayOutputStream reqBuf = new ByteArrayOutputStream();
        int b, nl = 0;
        while ((b = s.getInputStream().read()) >= 0) {
          reqBuf.write(b);
          if (b == '\n') { if (nl == 1) break; nl = 1; } else if (b != '\r') nl = 0;
        }
        String reason = status == 200 ? "OK" : status == 302 ? "Found" : status == 403 ? "Forbidden" : "X";
        StringBuilder sb = new StringBuilder("HTTP/1.1 " + status + " " + reason + "\r\n");
        if (headers != null) for (String h : headers) sb.append(h).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n\r\n");
        OutputStream os = s.getOutputStream();
        os.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        os.write(body);
        os.flush();
        s.close();
        ss.close();
      } catch (Exception ignored) {}
    });
    t.setDaemon(true); t.start();
    return "http://127.0.0.1:" + port;
  }

  private static AnalyzeUrlLite req(String url) {
    return AnalyzeUrlLite.parse(url, url, "kw", 1);
  }

  @Test public void fetchFollowsHttpRedirectChain() throws Exception {
    // 第一跳 302 → 第二跳 200
    String second = serveOnce(200, null, "final-page".getBytes(StandardCharsets.UTF_8));
    String first = serveOnce(302, new String[]{"Location: " + second}, new byte[0]);
    HttpProbe.Response r = HttpProbe.fetch(req(first + "/start"), Collections.<String, String>emptyMap(), 8000);
    assertEquals(200, r.code);
    assertTrue(r.body.contains("final-page"));
  }

  @Test public void fetchReturns403EmptyBodyNotThrow() throws Exception {
    String url = serveOnce(403, null, new byte[0]);
    HttpProbe.Response r = HttpProbe.fetch(req(url), Collections.<String, String>emptyMap(), 8000);
    assertEquals(403, r.code);
  }

  @Test public void customOkStatusRangesAccept403() {
    CheckSourceSettings s = new CheckSourceSettings();
    s.okStatusRanges = "200-399,403";
    assertTrue(s.isHttpOk(403));
    assertFalse(s.isHttpOk(500));
    CheckSourceSettings def = new CheckSourceSettings();
    assertFalse(def.isHttpOk(403));
    assertTrue(def.isHttpOk(200));
    assertTrue(def.isHttpOk(399));
  }

  @Test public void cloudflareChallengeBodyRejected() throws Exception {
    String url = serveOnce(200, null,
        "<html>Just a moment... cloudflare</html>".getBytes(StandardCharsets.UTF_8));
    HttpProbe.Response r = HttpProbe.fetch(req(url), Collections.<String, String>emptyMap(), 8000);
    assertEquals(200, r.code);
    assertTrue(HttpProbe.isCloudflareChallenge(r.body));
    assertFalse(HttpProbe.httpOk(r.code, r.body));
  }

  @Test public void gzipBodyDecompressed() throws Exception {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
      gz.write("gzip-content-ok".getBytes(StandardCharsets.UTF_8));
    }
    String url = serveOnce(200, new String[]{"Content-Encoding: gzip"}, bos.toByteArray());
    HttpProbe.Response r = HttpProbe.fetch(req(url), Collections.<String, String>emptyMap(), 8000);
    assertEquals(200, r.code);
    assertTrue(r.body.contains("gzip-content-ok"));
  }
}
