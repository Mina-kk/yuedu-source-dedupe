package com.mina.yuedu.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public final class FetchManager {
  public static final int MAX_CONCURRENCY = 10;

  public static final class QueueState {
    private final Deque<String> pending;
    private final int limit, total;
    private int inFlight, completed, succeeded, failed, sources, retrying;
    private boolean cancelled, keepLoaded;
    private final Map<String, Integer> hostInFlight = new HashMap<String, Integer>();

    public QueueState(List<String> urls, int limit) {
      if (limit < 1 || limit > MAX_CONCURRENCY) {
        throw new IllegalArgumentException("concurrency 1.." + MAX_CONCURRENCY);
      }
      pending = new ArrayDeque<String>(urls);
      this.limit = limit;
      total = urls.size();
    }

    public synchronized String takeNext() {
      if (cancelled || inFlight >= limit || pending.isEmpty()) return null;
      int n = pending.size();
      for (int i = 0; i < n; i++) {
        String url = pending.removeFirst();
        String host = FetchPolicy.hostKey(url);
        int used = hostInFlight.containsKey(host) ? hostInFlight.get(host) : 0;
        if (used < FetchPolicy.MAX_PER_HOST) {
          hostInFlight.put(host, used + 1);
          inFlight++;
          return url;
        }
        pending.addLast(url);
      }
      return null;
    }

    public synchronized void complete(boolean ok, int count) {
      if (inFlight > 0) inFlight--;
      completed++;
      if (ok) succeeded++;
      else failed++;
      sources += Math.max(0, count);
    }

    public synchronized void releaseHost(String url) {
      String host = FetchPolicy.hostKey(url);
      Integer used = hostInFlight.get(host);
      if (used == null) return;
      if (used <= 1) hostInFlight.remove(host);
      else hostInFlight.put(host, used - 1);
    }

    public synchronized void markRetrying(boolean on) {
      if (on) retrying++;
      else if (retrying > 0) retrying--;
    }

    public synchronized void cancel(boolean keep) {
      cancelled = true;
      keepLoaded = keep;
      pending.clear();
    }

    public synchronized boolean shouldKeepLoaded() { return keepLoaded; }
    public synchronized boolean isCancelled() { return cancelled; }
    public synchronized boolean isDone() {
      return (pending.isEmpty() && inFlight == 0) || (cancelled && inFlight == 0);
    }
    public synchronized FetchProgress progress() {
      return new FetchProgress(total, completed, succeeded, failed, sources, !isDone(), cancelled, retrying);
    }
  }

  private final Set<HttpURLConnection> active = Collections.synchronizedSet(new HashSet<HttpURLConnection>());
  private volatile QueueState state;
  private volatile FetchListener listener;
  private final AtomicInteger workers = new AtomicInteger();
  private final int maxBytes = 32 * 1024 * 1024;

  public synchronized void start(List<String> urls, int concurrency, FetchListener l) {
    if (state != null && !state.isDone()) throw new IllegalStateException("task running");
    listener = l;
    if (urls == null || urls.isEmpty()) {
      state = new QueueState(java.util.Collections.<String>emptyList(), 1);
      listener.onProgress(state.progress());
      listener.onFinished(false, false);
      return;
    }
    int n = Math.max(1, Math.min(MAX_CONCURRENCY, concurrency));
    n = Math.min(n, urls.size());
    state = new QueueState(urls, n);
    listener.onProgress(state.progress());
    workers.set(n);
    for (int i = 0; i < n; i++) new Thread(this::work, "source-fetch-" + i).start();
  }

  public void cancel(boolean keep) {
    QueueState s = state;
    if (s == null) return;
    s.cancel(keep);
    synchronized (active) {
      for (HttpURLConnection c : new ArrayList<HttpURLConnection>(active)) c.disconnect();
    }
  }

  private void work() {
    try {
      while (true) {
        if (state.isCancelled()) break;
        String url = state.takeNext();
        if (url == null) {
          if (state.isDone() || state.isCancelled()) break;
          sleepQuiet(40L);
          continue;
        }
        try {
          String body = fetchWithRetry(url);
          int count = estimateCount(body);
          try {
            listener.onItem(url, body);
          } catch (Exception parseErr) {
            listener.onFailure(url, formatErr(parseErr));
          }
          state.complete(true, count);
        } catch (Exception e) {
          state.complete(false, 0);
          listener.onFailure(url, formatErr(e));
        } finally {
          state.releaseHost(url);
        }
        listener.onProgress(state.progress());
      }
    } finally {
      if (workers.decrementAndGet() == 0) {
        listener.onFinished(state.isCancelled(), state.shouldKeepLoaded());
      }
    }
  }

  private String fetchWithRetry(String url) throws Exception {
    Exception last = null;
    for (int attempt = 1; attempt <= FetchPolicy.MAX_ATTEMPTS; attempt++) {
      if (state != null && state.isCancelled()) {
        throw last != null ? last : new IOException("cancelled");
      }
      try {
        return doFetch(url);
      } catch (Exception e) {
        last = e;
        boolean more = attempt < FetchPolicy.MAX_ATTEMPTS && FetchPolicy.isRetryable(e)
            && (state == null || !state.isCancelled());
        if (!more) throw e;
        state.markRetrying(true);
        try {
          listener.onProgress(state.progress());
          sleepQuiet(FetchPolicy.backoffMs(attempt));
        } finally {
          state.markRetrying(false);
        }
      }
    }
    throw last != null ? last : new IOException("fetch failed");
  }

  private static String formatErr(Exception e) {
    String msg = e.getMessage();
    if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
    return e.getClass().getSimpleName() + ":" + msg;
  }

  private void sleepQuiet(long ms) {
    if (ms <= 0L) return;
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private int estimateCount(String body) {
    if (body == null) return 0;
    int n = 0;
    int from = 0;
    while (true) {
      int i = body.indexOf("bookSourceUrl", from);
      if (i < 0) break;
      n++;
      from = i + 13;
      if (n > 50_000) break;
    }
    return n;
  }

  private String doFetch(String url) throws Exception {
    HttpURLConnection c = open(url);
    active.add(c);
    try {
      int code = c.getResponseCode();
      int hops = 0;
      while (code >= 300 && code < 400 && hops < 5) {
        String loc = c.getHeaderField("Location");
        if (loc == null || loc.trim().isEmpty()) break;
        URL next = new URL(c.getURL(), loc);
        active.remove(c);
        c.disconnect();
        c = open(next.toString());
        active.add(c);
        code = c.getResponseCode();
        hops++;
      }
      if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
      InputStream raw = c.getErrorStream() != null && code >= 400 ? c.getErrorStream() : c.getInputStream();
      if (raw == null) throw new IOException("empty body");
      InputStream in = wrapMaybeGzip(raw, c.getContentEncoding());
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(b)) >= 0) {
          total += n;
          if (total > maxBytes) throw new IOException("response too large (" + total + ")");
          out.write(b, 0, n);
        }
        if (total == 0) throw new IOException("empty body");
        return out.toString("UTF-8");
      } finally {
        in.close();
      }
    } finally {
      active.remove(c);
      c.disconnect();
    }
  }

  private HttpURLConnection open(String url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
    c.setConnectTimeout(FetchPolicy.CONNECT_TIMEOUT_MS);
    c.setReadTimeout(FetchPolicy.READ_TIMEOUT_MS);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", FetchPolicy.USER_AGENT);
    c.setRequestProperty("Accept", "application/json,text/plain,*/*");
    c.setRequestProperty("Accept-Encoding", "gzip");
    c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
    String origin = FetchPolicy.origin(url);
    if (origin != null) c.setRequestProperty("Referer", origin);
    return c;
  }

  private InputStream wrapMaybeGzip(InputStream raw, String enc) throws IOException {
    boolean claimGzip = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
    if (!claimGzip) return raw;
    PushbackInputStream pb = new PushbackInputStream(raw, 2);
    int b1 = pb.read();
    int b2 = pb.read();
    if (b1 < 0) return pb;
    if (b2 < 0) {
      pb.unread(b1);
      return pb;
    }
    pb.unread(new byte[]{(byte) b1, (byte) b2});
    if (b1 == 0x1f && b2 == 0x8b) return new GZIPInputStream(pb);
    return pb;
  }
}