package com.mina.yuedu.check;

import com.mina.yuedu.core.UrlNormalizer;
import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Check pipeline aligned with legado CheckSourceService semantics.
 * Phase-2: real HTTP for search/discovery, then opportunistic info/toc/content
 * URL chaining when response yields candidate links. JS-heavy sources are
 * marked js失效 when URL construction requires JS and no static URL remains.
 *
 * Layer-1 N-round probes run serially on the same worker. The pool is bounded
 * to the concurrency cap so 500 workers are not multiplied by extra per-source
 * executors.
 */
public final class CheckSourceEngine {
  public interface Listener {
    void onProgress(int done, int total, String currentName);
    void onProbe(SourceRecord source, int round, int rounds, int inFlight);
    void onItem(CheckSourceResult result);
    void onFinished(List<CheckSourceResult> results);
  }

  private static final long SITE_CACHE_TTL_MS = 30_000L;

  private final ExecutorService pool;
  private volatile boolean cancelled;
  private volatile Listener activeListener;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Map<String, CachedSite> siteCache = Collections.synchronizedMap(new HashMap<String, CachedSite>());

  public CheckSourceEngine(int concurrency) {
    int n = Math.max(1, Math.min(CheckSourceSettings.MAX_CONCURRENCY, concurrency));
    pool = new ThreadPoolExecutor(
        n, n,
        30L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(Math.max(n * 4, 64)),
        new ThreadPoolExecutor.CallerRunsPolicy());
  }

  public boolean isCancelled() { return cancelled; }

  public void cancel() {
    cancelled = true;
    HttpProbe.cancelAll();
    // Do not shutdownNow here: queued tasks must still start, see cancelled,
    // and decrement the latch. Otherwise checkAll waits forever.
  }

  public void close() {
    cancelled = true;
    pool.shutdownNow();
    HttpProbe.cancelAll();
  }

  public void checkAll(List<SourceRecord> sources, CheckSourceSettings settings, Listener listener) {
    settings.normalize();
    activeListener = listener;
    List<CheckSourceResult> out = Collections.synchronizedList(new ArrayList<CheckSourceResult>());
    AtomicDone done = new AtomicDone();
    int total = sources == null ? 0 : sources.size();
    if (total == 0) {
      listener.onFinished(out);
      pool.shutdownNow();
      return;
    }
    CountDownLatch latch = new CountDownLatch(total);
    for (SourceRecord s : sources) {
      Runnable task = () -> {
        inFlight.incrementAndGet();
        try {
          if (cancelled) return;
          CheckSourceResult r = checkOne(s, settings);
          if (!cancelled && r != null) {
            out.add(r);
            listener.onItem(r);
          }
        } finally {
          inFlight.decrementAndGet();
          int d = done.inc();
          if (!cancelled) listener.onProgress(d, total, s.getName());
          latch.countDown();
        }
      };
      try {
        pool.execute(task);
      } catch (RejectedExecutionException e) {
        task.run();
      }
    }
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    listener.onFinished(new ArrayList<CheckSourceResult>(out));
  }

  public CheckSourceResult checkOne(SourceRecord source, CheckSourceSettings settings) {
    settings.normalize();
    SourceKind kind = SourceKind.of(source);
    if (!settings.allows(kind)) {
      return new CheckSourceResult(source, CheckSourceResult.Status.SKIPPED, new ArrayList<String>(),
          "跳过类型", 0, kind, 0, 0, 0, 0, 0);
    }
    if (!UrlNormalizer.isHttpUrl(source.getUrl())) {
      List<String> groups = new ArrayList<String>();
      groups.add("网站失效");
      return new CheckSourceResult(source, CheckSourceResult.Status.SKIPPED, groups,
          "非 HTTP(S) 源，不参与最快竞争", 0, kind, 0, 0, 0, 0, 0);
    }
    int rounds = settings.probeRounds;
    List<CheckSourceResult> collected = new ArrayList<CheckSourceResult>(rounds);
    int success = 0;
    for (int i = 0; i < rounds; i++) {
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      notifyProbe(source, i + 1, rounds);
      CheckSourceResult once = doCheckOnce(source, settings, kind);
      if (once == null) return null;
      collected.add(once);
      if (once.status == CheckSourceResult.Status.SUCCESS) success++;
      if (once.groups != null && (once.groups.contains("网站失效") || once.groups.contains("校验超时"))) {
        break;
      }
      if (CheckAggregator.decided(rounds, success, collected.size())) break;
    }
    return CheckAggregator.combine(source, kind, rounds, collected);
  }

  private void notifyProbe(SourceRecord source, int round, int rounds) {
    Listener listener = activeListener;
    if (listener == null) return;
    try {
      listener.onProbe(source, round, rounds, inFlight.get());
    } catch (Exception ignored) {}
  }

  private CheckSourceResult doCheckOnce(SourceRecord source, CheckSourceSettings settings, SourceKind kind) {
    long start = System.currentTimeMillis();
    List<String> groups = new ArrayList<String>();
    try {
      groups.addAll(doCheck(source, settings, kind));
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      long cost = System.currentTimeMillis() - start;
      if (groups.contains("校验超时")) {
        return new CheckSourceResult(source, CheckSourceResult.Status.TIMEOUT, groups, "timeout", cost, kind);
      }
      if (groups.isEmpty()) {
        return new CheckSourceResult(source, CheckSourceResult.Status.SUCCESS, groups, "校验成功", cost, kind);
      }
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          String.join(",", groups), cost, kind);
    } catch (Exception e) {
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      groups.add("网站失效");
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          e.getMessage() == null ? "error" : e.getMessage(),
          System.currentTimeMillis() - start, kind);
    }
  }

  private List<String> doCheck(SourceRecord source, CheckSourceSettings settings, SourceKind kind) {
    List<String> bad = new ArrayList<String>();
    String base;
    try {
      // bookSourceUrl 的 #后缀是书源身份标签，不参与实际 HTTP 请求。
      base = UrlNormalizer.requestUrl(source.getUrl());
    } catch (Exception e) {
      bad.add("网站失效");
      return bad;
    }

    Map<String, String> headers = HttpProbe.parseSourceHeader(source.rawString("header"));
    int stepTimeout = (int) Math.min(Math.max(settings.timeoutMillis() / 3L, 8000L), 30000L);

    if (!probeSite(base, headers, Math.min(stepTimeout, 10000), settings.keyword)) {
      bad.add("网站失效");
      return uniq(bad);
    }
    if (Thread.currentThread().isInterrupted() || cancelled) return uniq(bad);

    String bookUrl = null;
    String tocUrl = null;
    String chapterUrl = null;

    if (settings.checkSearch) {
      String searchUrl = source.rawString("searchUrl");
      if (searchUrl == null || searchUrl.trim().isEmpty()) {
        bad.add("搜索链接规则为空");
      } else {
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(searchUrl, base, settings.keyword, 1);
        if (req.usesJs && (req.url == null || req.url.trim().isEmpty() || req.url.equals(base))) {
          bad.add("js失效");
        } else {
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
            if (!r.httpOk() || !hit.ok) bad.add("搜索失效");
            else if (!hit.bookUrls.isEmpty()) bookUrl = hit.bookUrls.get(0);
          } catch (Exception e) {
            bad.add(req.usesJs ? "js失效" : "搜索失效");
          }
        }
      }
    }
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    if (settings.checkDiscovery) {
      String explore = source.rawString("exploreUrl");
      Object ruleExplore = source.getRaw().get("ruleExplore");
      if (explore != null && !explore.trim().isEmpty()) {
        String first = firstExploreUrl(explore);
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(first, base, settings.keyword, 1);
        if (req.usesJs && (req.url == null || req.url.isEmpty())) {
          bad.add("js失效");
        } else {
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
            boolean ruleOk = ResultInspect.hasRule(ruleExplore);
            if ((!r.httpOk() || !hit.ok) && !ruleOk) bad.add("发现失效");
            else if (!r.httpOk() || !hit.ok) bad.add("发现失效");
            else if (bookUrl == null && !hit.bookUrls.isEmpty()) bookUrl = hit.bookUrls.get(0);
          } catch (Exception e) {
            bad.add(req.usesJs ? "js失效" : "发现失效");
          }
        }
      }
    }
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    Map<String, Object> ruleInfo = source.ruleMap("ruleBookInfo");
    Map<String, Object> ruleToc = source.ruleMap("ruleToc");
    Map<String, Object> ruleContent = source.ruleMap("ruleContent");

    if (settings.checkInfo) {
      if (!ResultInspect.hasRule(ruleInfo) && bookUrl == null) {
        bad.add("详情失效");
      } else if (bookUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(bookUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectBookPage(r.body);
          if (!r.httpOk() || !hit.ok) bad.add("详情失效");
          else {
            if (!hit.bookUrls.isEmpty()) tocUrl = hit.bookUrls.get(0);
            String initToc = ResultInspect.ruleString(ruleInfo, "tocUrl");
            if (initToc != null && !initToc.trim().isEmpty() && !initToc.contains("@js") && !initToc.contains("<js>")) {
              AnalyzeUrlLite tocReq = AnalyzeUrlLite.parse(initToc, r.finalUrl != null ? r.finalUrl : bookUrl, settings.keyword, 1);
              tocUrl = tocReq.url;
            } else if (tocUrl == null) {
              tocUrl = r.finalUrl != null ? r.finalUrl : bookUrl;
            }
          }
        } catch (Exception e) {
          bad.add("详情失效");
        }
      }
    }
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    if (settings.checkCategory) {
      if (!ResultInspect.hasRule(ruleToc) && tocUrl == null) {
        bad.add("目录失效");
      } else if (tocUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(tocUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : tocUrl);
          if (!r.httpOk() || !hit.ok) bad.add("目录失效");
          else if (!hit.bookUrls.isEmpty()) chapterUrl = hit.bookUrls.get(0);
        } catch (Exception e) {
          bad.add("目录失效");
        }
      }
    }
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    if (settings.checkContent) {
      String contentFail = kind.contentFailGroup();
      if (!ResultInspect.hasRule(ruleContent) && chapterUrl == null) {
        bad.add(contentFail);
      } else if (chapterUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(chapterUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectContent(r.body, kind);
          if (!r.httpOk() || !hit.ok) bad.add(contentFail);
        } catch (Exception e) {
          bad.add(contentFail);
        }
      }
    }

    return uniq(bad);
  }

  private static List<String> uniq(List<String> bad) {
    List<String> out = new ArrayList<String>();
    if (bad == null) return out;
    for (String g : bad) if (g != null && !out.contains(g)) out.add(g);
    return out;
  }

  private boolean probeSite(String base, Map<String, String> headers, int timeoutMs, String keyword) {
    String host;
    try {
      host = UrlNormalizer.hostKey(base);
    } catch (Exception e) {
      host = base;
    }
    long now = System.currentTimeMillis();
    CachedSite cached = siteCache.get(host);
    if (cached != null && now - cached.at < SITE_CACHE_TTL_MS) return cached.ok;
    boolean ok;
    try {
      AnalyzeUrlLite baseReq = AnalyzeUrlLite.parse(base, base, keyword, 1);
      HttpProbe.Response br = HttpProbe.fetch(baseReq, headers, timeoutMs);
      ok = br.httpOk();
    } catch (Exception e) {
      ok = false;
    }
    siteCache.put(host, new CachedSite(ok, now));
    return ok;
  }

  private static String firstExploreUrl(String explore) {
    String s = explore.trim();
    String[] lines = s.split("\\r?\\n");
    for (String line : lines) {
      String t = line.trim();
      if (t.isEmpty()) continue;
      int sep = t.indexOf("::");
      if (sep >= 0 && sep + 2 < t.length()) return t.substring(sep + 2).trim();
      if (t.startsWith("http") || t.contains("{{") || t.contains("/")) return t;
    }
    return s;
  }

  private static final class CachedSite {
    final boolean ok;
    final long at;
    CachedSite(boolean ok, long at) { this.ok = ok; this.at = at; }
  }

  private static final class AtomicDone {
    private int v;
    synchronized int inc() { return ++v; }
  }
}