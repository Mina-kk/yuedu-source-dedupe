package com.mina.yuedu.network;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FetchManagerConcurrencyTest {
  @Test public void acceptsUpToTenWorkers() {
    assertEquals(10, FetchManager.MAX_CONCURRENCY);
    FetchManager.QueueState ten = new FetchManager.QueueState(java.util.Collections.singletonList("https://a"), 10);
    assertEquals(1, ten.progress().getTotal());
  }

  @Test public void rejectsAboveTen() {
    try {
      new FetchManager.QueueState(java.util.Collections.singletonList("https://a"), 11);
      fail("expected illegal concurrency");
    } catch (IllegalArgumentException expected) {
      assertEquals("concurrency 1..10", expected.getMessage());
    }
  }

  @Test public void sameHostTakenOneAtATime() {
    FetchManager.QueueState q = new FetchManager.QueueState(Arrays.asList(
        "https://www.yckceo.com/a.json",
        "https://yckceo.com/b.json",
        "https://www.yckceo.com/c.json"), 10);
    String first = q.takeNext();
    assertEquals("https://www.yckceo.com/a.json", first);
    assertEquals(null, q.takeNext());
    q.releaseHost(first);
    String second = q.takeNext();
    assertEquals("https://yckceo.com/b.json", second);
    q.releaseHost(second);
    assertEquals("https://www.yckceo.com/c.json", q.takeNext());
  }

  @Test public void differentHostsCanRunTogether() {
    FetchManager.QueueState q = new FetchManager.QueueState(Arrays.asList(
        "https://a.example/1.json",
        "https://b.example/2.json"), 10);
    assertEquals("https://a.example/1.json", q.takeNext());
    assertEquals("https://b.example/2.json", q.takeNext());
    assertEquals(null, q.takeNext());
  }

  @Test public void threeConcurrentUrlsAllDeliverBeforeFinished() throws Exception {
    try (TinyJsonServer server = TinyJsonServer.start()) {
      List<String> urls = Arrays.asList(server.url("a"), server.url("b"), server.url("c"));
      FetchManager fm = new FetchManager();
      Set<String> got = Collections.synchronizedSet(new LinkedHashSet<String>());
      AtomicInteger finished = new AtomicInteger();
      CountDownLatch done = new CountDownLatch(1);
      fm.start(urls, 10, new FetchListener() {
        @Override public void onProgress(FetchProgress p) {}
        @Override public void onItem(String url, String body) {
          SourceParser.ParseResult p = SourceParser.parseArray(body, 0);
          if (!p.getRecords().isEmpty()) got.add(p.getRecords().get(0).getName());
        }
        @Override public void onFailure(String url, String message) {
          fail(url + " " + message);
        }
        @Override public void onFinished(boolean cancelled, boolean keepLoaded) {
          finished.incrementAndGet();
          done.countDown();
        }
      });
      assertTrue(done.await(8, TimeUnit.SECONDS));
      assertEquals(1, finished.get());
      assertEquals(new LinkedHashSet<String>(Arrays.asList("A", "B", "C")), got);
    }
  }

  @Test public void retriesTransientHttpThenSucceeds() throws Exception {
    try (FlakyJsonServer server = FlakyJsonServer.start(1)) {
      CountDownLatch done = new CountDownLatch(1);
      AtomicInteger items = new AtomicInteger();
      AtomicInteger fails = new AtomicInteger();
      FetchManager fm = new FetchManager();
      fm.start(Collections.singletonList(server.url()), 1, new FetchListener() {
        @Override public void onProgress(FetchProgress p) {}
        @Override public void onItem(String url, String body) { items.incrementAndGet(); }
        @Override public void onFailure(String url, String message) { fails.incrementAndGet(); }
        @Override public void onFinished(boolean cancelled, boolean keepLoaded) { done.countDown(); }
      });
      assertTrue(done.await(6, TimeUnit.SECONDS));
      assertEquals(1, items.get());
      assertEquals(0, fails.get());
      assertTrue(server.hits() >= 2);
    }
  }

  @Test public void hardHttpIsNotRetried() throws Exception {
    try (FlakyJsonServer server = FlakyJsonServer.always(404)) {
      CountDownLatch done = new CountDownLatch(1);
      AtomicInteger fails = new AtomicInteger();
      FetchManager fm = new FetchManager();
      fm.start(Collections.singletonList(server.url()), 1, new FetchListener() {
        @Override public void onProgress(FetchProgress p) {}
        @Override public void onItem(String url, String body) { fail("404 should not parse"); }
        @Override public void onFailure(String url, String message) { fails.incrementAndGet(); }
        @Override public void onFinished(boolean cancelled, boolean keepLoaded) { done.countDown(); }
      });
      assertTrue(done.await(4, TimeUnit.SECONDS));
      assertEquals(1, fails.get());
      assertEquals(1, server.hits());
    }
  }

  private static final class TinyJsonServer implements AutoCloseable {
    private final ServerSocket socket;
    private final Thread worker;
    private volatile boolean closed;

    static TinyJsonServer start() throws Exception {
      return new TinyJsonServer();
    }

    private TinyJsonServer() throws Exception {
      socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
      worker = new Thread(this::serve, "tiny-json");
      worker.setDaemon(true);
      worker.start();
    }

    String url(String name) {
      return "http://127.0.0.1:" + socket.getLocalPort() + "/" + name + ".json";
    }

    private void serve() {
      while (!closed) {
        try {
          Socket client = socket.accept();
          new Thread(() -> handle(client), "tiny-json-client").start();
        } catch (Exception e) {
          if (!closed) break;
        }
      }
    }

    private void handle(Socket client) {
      try (Socket c = client) {
        c.setSoTimeout(3000);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int prev = 0, cur;
        while ((cur = in.read()) >= 0) {
          head.write(cur);
          if (prev == '\n' && cur == '\n') break;
          if (prev == '\r' && cur == '\n' && head.size() >= 4) {
            byte[] b = head.toByteArray();
            if (b.length >= 4 && b[b.length - 4] == '\r' && b[b.length - 3] == '\n') break;
          }
          prev = cur;
        }
        String req = new String(head.toByteArray(), StandardCharsets.US_ASCII);
        String path = "/";
        int sp = req.indexOf(' ');
        int sp2 = req.indexOf(' ', sp + 1);
        if (sp >= 0 && sp2 > sp) path = req.substring(sp + 1, sp2);
        String name = "X";
        if (path.contains("/a.json")) name = "A";
        else if (path.contains("/b.json")) name = "B";
        else if (path.contains("/c.json")) name = "C";
        byte[] body = ("[{\"bookSourceName\":\"" + name + "\",\"bookSourceUrl\":\"https://" + name.toLowerCase() + ".example\"}]")
            .getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
            + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
      } catch (Exception ignored) {}
    }

    @Override public void close() {
      closed = true;
      try { socket.close(); } catch (Exception ignored) {}
    }
  }

  private static final class FlakyJsonServer implements AutoCloseable {
    private final ServerSocket socket;
    private final Thread worker;
    private final int failFirst;
    private final int alwaysCode;
    private volatile boolean closed;
    private final AtomicInteger hits = new AtomicInteger();

    static FlakyJsonServer start(int failFirst) throws Exception {
      return new FlakyJsonServer(failFirst, 0);
    }

    static FlakyJsonServer always(int code) throws Exception {
      return new FlakyJsonServer(0, code);
    }

    private FlakyJsonServer(int failFirst, int alwaysCode) throws Exception {
      this.failFirst = failFirst;
      this.alwaysCode = alwaysCode;
      socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
      worker = new Thread(this::serve, "flaky-json");
      worker.setDaemon(true);
      worker.start();
    }

    String url() {
      return "http://localhost:" + socket.getLocalPort() + "/pack.json";
    }

    int hits() { return hits.get(); }

    private void serve() {
      while (!closed) {
        try {
          Socket client = socket.accept();
          new Thread(() -> handle(client), "flaky-json-client").start();
        } catch (Exception e) {
          if (!closed) break;
        }
      }
    }

    private void handle(Socket client) {
      try (Socket c = client) {
        c.setSoTimeout(3000);
        InputStream in = c.getInputStream();
        while (in.read() >= 0) {
          // drain request; Tiny server already proved header parsing
          if (in.available() == 0) break;
        }
        int n = hits.incrementAndGet();
        OutputStream out = c.getOutputStream();
        if (alwaysCode != 0) {
          byte[] msg = ("nope").getBytes(StandardCharsets.US_ASCII);
          out.write(("HTTP/1.1 " + alwaysCode + " ERR\r\nContent-Length: " + msg.length
              + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          out.write(msg);
        } else if (n <= failFirst) {
          byte[] msg = "busy".getBytes(StandardCharsets.US_ASCII);
          out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + msg.length
              + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          out.write(msg);
        } else {
          byte[] body = "[{\"bookSourceName\":\"A\",\"bookSourceUrl\":\"https://a.example\"}]"
              .getBytes(StandardCharsets.UTF_8);
          out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
              + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          out.write(body);
        }
        out.flush();
      } catch (Exception ignored) {}
    }

    @Override public void close() {
      closed = true;
      try { socket.close(); } catch (Exception ignored) {}
    }
  }
}
