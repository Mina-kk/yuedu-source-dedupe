package com.mina.yuedu.check;

import com.mina.yuedu.core.UrlNormalizer;
import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Check pipeline aligned with legado CheckSourceService semantics.
 * Phase-2: real HTTP for search/discovery, then opportunistic info/toc/content
 * URL chaining when response yields candidate links. JS-heavy sources are
 * marked js失效 when URL construction requires JS and no static URL remains.
 */
public final class CheckSourceEngine {
  public interface Listener {
    void onProgress(int done, int total, String currentName);
    void onItem(CheckSourceResult result);
    void onFinished(List<CheckSourceResult> results);
  }

  private final ExecutorService pool;
  private final Set<Future<?>> runningChecks = Collections.synchronizedSet(new HashSet<>());
  private volatile boolean cancelled;

  public CheckSourceEngine(int concurrency) {
    pool = Executors.newFixedThreadPool(Math.max(1,
        Math.min(CheckSourceSettings.MAX_CONCURRENCY, concurrency)));
  }

  public boolean isCancelled() { return cancelled; }

  public void cancel() {
    cancelled = true;
    synchronized (runningChecks) {
      for (Future<?> future : new ArrayList<>(runningChecks)) future.cancel(true);
      runningChecks.clear();
    }
    HttpProbe.cancelAll();
    // 不在这里 shutdownNow：队列任务需要启动后看到 cancelled 并递减 latch，
    // 否则 checkAll 会永久等待尚未开始的任务。
  }

  public void checkAll(List<SourceRecord> sources, CheckSourceSettings settings, Listener listener) {
    settings.normalize();
    List<CheckSourceResult> out = Collections.synchronizedList(new ArrayList<>());
    AtomicDone done = new AtomicDone();
    int total = sources.size();
    if (total == 0) {
      listener.onFinished(out);
      pool.shutdownNow();
      return;
    }
    CountDownLatch latch = new CountDownLatch(total);
    for (SourceRecord s : sources) {
      pool.execute(() -> {
        try {
          if (cancelled) return;
          CheckSourceResult r = checkOne(s, settings);
          if (!cancelled && r != null) {
            out.add(r);
            listener.onItem(r);
          }
        } finally {
          int d = done.inc();
          if (!cancelled) listener.onProgress(d, total, s.getName());
          latch.countDown();
        }
      });
    }
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      pool.shutdownNow();
      HttpProbe.cancelAll();
    }
    listener.onFinished(new ArrayList<>(out));
  }

  private CheckSourceResult checkOne(SourceRecord source, CheckSourceSettings settings) {
    long start = System.currentTimeMillis();
    List<String> groups = new ArrayList<>();
    ExecutorService one = Executors.newSingleThreadExecutor();
    Future<List<String>> future = null;
    try {
      if (cancelled) return null;
      future = one.submit(() -> doCheck(source, settings));
      runningChecks.add(future);
      if (cancelled) {
        future.cancel(true);
        return null;
      }
      try {
        groups.addAll(future.get(settings.timeoutMillis(), TimeUnit.MILLISECONDS));
      } catch (TimeoutException te) {
        future.cancel(true);
        groups.add("校验超时");
        return new CheckSourceResult(source, CheckSourceResult.Status.TIMEOUT, groups, "timeout",
            System.currentTimeMillis() - start);
      } catch (CancellationException ce) {
        return null;
      }
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      long cost = System.currentTimeMillis() - start;
      if (groups.isEmpty()) {
        return new CheckSourceResult(source, CheckSourceResult.Status.SUCCESS, groups, "校验成功", cost);
      }
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          String.join(",", groups), cost);
    } catch (Exception e) {
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      groups.add("网站失效");
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          e.getMessage() == null ? "error" : e.getMessage(),
          System.currentTimeMillis() - start);
    } finally {
      if (future != null) runningChecks.remove(future);
      one.shutdownNow();
    }
  }

  private List<String> doCheck(SourceRecord source, CheckSourceSettings settings) {
    List<String> bad = new ArrayList<>();
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

    // base host probe
    try {
      AnalyzeUrlLite baseReq = AnalyzeUrlLite.parse(base, base, settings.keyword, 1);
      HttpProbe.Response br = HttpProbe.fetch(baseReq, headers, Math.min(stepTimeout, 10000));
      if (!br.httpOk()) bad.add("网站失效");
    } catch (Exception e) {
      bad.add("网站失效");
    }
    if (Thread.currentThread().isInterrupted()) return bad;

    String bookUrl = null;
    String tocUrl = null;
    String chapterUrl = null;

    if (settings.checkSearch) {
      String searchUrl = source.rawString("searchUrl");
      Object ruleSearch = source.getRaw().get("ruleSearch");
      if (searchUrl == null || searchUrl.trim().isEmpty()) {
        bad.add("搜索链接规则为空");
      } else {
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(searchUrl, base, settings.keyword, 1);
        if (req.usesJs && (req.url == null || req.url.trim().isEmpty() || req.url.equals(base))) {
          bad.add("js失效");
        } else if (!ResultInspect.hasRule(ruleSearch) && !req.usesJs) {
          // still try network if URL exists
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
            if (!r.httpOk() || !hit.ok) bad.add("搜索失效");
            else if (!hit.bookUrls.isEmpty()) bookUrl = hit.bookUrls.get(0);
          } catch (Exception e) {
            bad.add("搜索失效");
          }
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
    if (Thread.currentThread().isInterrupted()) return bad;

    if (settings.checkDiscovery) {
      String explore = source.rawString("exploreUrl");
      Object ruleExplore = source.getRaw().get("ruleExplore");
      if (explore != null && !explore.trim().isEmpty()) {
        // exploreUrl may be multi-line kinds; take first non-empty line / first kind url
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
      // empty explore is allowed (many sources have no discovery)
    }
    if (Thread.currentThread().isInterrupted()) return bad;

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
            // prefer tocUrl from response, else book url as toc base
            if (!hit.bookUrls.isEmpty()) {
              // may contain toc/chapter candidates
              tocUrl = hit.bookUrls.get(0);
            }
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
      // if only rule present and no bookUrl from search/discovery, keep soft-pass on rule existence
    }
    if (Thread.currentThread().isInterrupted()) return bad;

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
    if (Thread.currentThread().isInterrupted()) return bad;

    if (settings.checkContent) {
      if (!ResultInspect.hasRule(ruleContent) && chapterUrl == null) {
        bad.add("正文失效");
      } else if (chapterUrl != null) {
        try {
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(chapterUrl, base, settings.keyword, 1);
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          ResultInspect.Hit hit = ResultInspect.inspectChapterContent(r.body);
          if (!r.httpOk() || !hit.ok) bad.add("正文失效");
        } catch (Exception e) {
          bad.add("正文失效");
        }
      }
    }

    // de-dup groups while preserving order
    List<String> uniq = new ArrayList<>();
    for (String g : bad) if (!uniq.contains(g)) uniq.add(g);
    return uniq;
  }

  private static String firstExploreUrl(String explore) {
    String s = explore.trim();
    // formats: "name::url\n..." or JSON or plain url
    String[] lines = s.split("\\r?\\n");
    for (String line : lines) {
      String t = line.trim();
      if (t.isEmpty()) continue;
      int sep = t.indexOf("::");
      if (sep >= 0 && sep + 2 < t.length()) return t.substring(sep + 2).trim();
      if (t.startsWith("http") || t.contains("{{") || t.contains("{{") || t.contains("/")) return t;
    }
    return s;
  }

  private static final class AtomicDone {
    private int v;
    synchronized int inc() { return ++v; }
  }
}
