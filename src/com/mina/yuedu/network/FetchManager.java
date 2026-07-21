package com.mina.yuedu.network;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class FetchManager {
    public static final class QueueState {
        private final Deque<String> pending;
        private final int limit, total;
        private int inFlight, completed, succeeded, failed, sources;
        private boolean cancelled, keepLoaded;

        public QueueState(List<String> urls, int limit) {
            if (limit < 1 || limit > 5) throw new IllegalArgumentException("concurrency 1..5");
            pending = new ArrayDeque<>(urls);
            this.limit = limit;
            total = urls.size();
        }

        public synchronized String takeNext() {
            if (cancelled || inFlight >= limit || pending.isEmpty()) return null;
            inFlight++;
            return pending.removeFirst();
        }

        public synchronized void complete(boolean ok, int count) {
            if (inFlight > 0) inFlight--;
            completed++;
            if (ok) succeeded++;
            else failed++;
            sources += Math.max(0, count);
        }

        public synchronized void cancel(boolean keep) {
            cancelled = true;
            keepLoaded = keep;
            pending.clear();
        }

        public synchronized int getInFlight() {
            return inFlight;
        }

        public synchronized boolean shouldKeepLoaded() {
            return keepLoaded;
        }

        public synchronized boolean isCancelled() {
            return cancelled;
        }

        public synchronized boolean isDone() {
            return (pending.isEmpty() && inFlight == 0) || (cancelled && inFlight == 0);
        }

        public synchronized FetchProgress progress() {
            return new FetchProgress(total, completed, succeeded, failed, sources, !isDone(), cancelled);
        }
    }

    public interface StreamConsumer {
        /**
         * Consume a successful network response as a stream.
         * Return accepted source count (best-effort). Throw to mark failure.
         */
        int consume(String url, InputStream body) throws Exception;
    }

    private final Set<HttpURLConnection> active = Collections.synchronizedSet(new HashSet<HttpURLConnection>());
    private volatile QueueState state;
    private volatile FetchListener listener;
    private volatile StreamConsumer streamConsumer;
    private final AtomicInteger workers = new AtomicInteger();
    // Serialize heavy parse work even when downloads are concurrent.
    private final Object parseLock = new Object();

    public synchronized void start(List<String> urls, int concurrency, FetchListener l) {
        start(urls, concurrency, l, null);
    }

    public synchronized void start(List<String> urls, int concurrency, FetchListener l, StreamConsumer consumer) {
        if (state != null && !state.isDone()) throw new IllegalStateException("task running");
        state = new QueueState(urls, concurrency);
        listener = l;
        streamConsumer = consumer;
        listener.onProgress(state.progress());
        workers.set(concurrency);
        for (int i = 0; i < concurrency; i++) new Thread(this::work, "source-fetch-" + i).start();
    }

    public void cancel(boolean keep) {
        QueueState s = state;
        if (s == null) return;
        s.cancel(keep);
        synchronized (active) {
            for (HttpURLConnection c : new ArrayList<>(active)) c.disconnect();
        }
    }

    private void work() {
        try {
            while (true) {
                String url = state.takeNext();
                if (url == null) break;
                try {
                    int count = fetchAndConsume(url);
                    state.complete(true, count);
                } catch (Exception e) {
                    state.complete(false, 0);
                    listener.onFailure(url, e.getClass().getSimpleName() + ":" + e.getMessage());
                }
                listener.onProgress(state.progress());
            }
        } finally {
            if (workers.decrementAndGet() == 0) {
                listener.onFinished(state.isCancelled(), state.shouldKeepLoaded());
            }
        }
    }

    private int fetchAndConsume(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        active.add(c);
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
            );
            c.setRequestProperty("Accept", "application/json,text/plain,*/*");
            c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "identity");
            try {
                URL u = new URL(url);
                c.setRequestProperty("Referer", u.getProtocol() + "://" + u.getHost() + "/");
            } catch (Exception ignored) {
            }
            int code = c.getResponseCode();
            InputStream raw = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (raw == null) throw new IOException("HTTP " + code + " empty body");
            try (InputStream in = new BufferedInputStream(raw, 65536)) {
                if (code >= 400) {
                    String err = readLimited(in, 4096);
                    throw new IOException("HTTP " + code + ": " + err);
                }
                if (streamConsumer != null) {
                    // Parse one large body at a time to cap peak memory.
                    synchronized (parseLock) {
                        return streamConsumer.consume(url, in);
                    }
                }
                // Legacy fallback: materialize body string and hand off.
                String body = readAll(in);
                listener.onItem(url, body);
                return 0;
            }
        } finally {
            active.remove(c);
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        return out.toString("UTF-8");
    }

    private static String readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int n;
        int total = 0;
        while (total < max && (n = in.read(b, 0, Math.min(b.length, max - total))) >= 0) {
            out.write(b, 0, n);
            total += n;
        }
        return out.toString("UTF-8");
    }

    /** Utility for callers that still need small text bodies (e.g. YCK HTML discovery). */
    public static String fetchText(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(45000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
            );
            c.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*");
            int code = c.getResponseCode();
            InputStream raw = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (raw == null) throw new IOException("HTTP " + code + " empty body");
            try (InputStream in = new BufferedInputStream(raw, 8192)) {
                if (code >= 400) throw new IOException("HTTP " + code);
                return readLimited(in, Math.max(1024, maxBytes));
            }
        } finally {
            c.disconnect();
        }
    }
}
