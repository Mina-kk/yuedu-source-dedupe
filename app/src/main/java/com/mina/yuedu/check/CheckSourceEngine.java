package com.mina.yuedu.check;

import com.mina.yuedu.core.UrlNormalizer;
import com.mina.yuedu.model.SourceRecord;
import com.mina.yuedu.network.SystemProxy;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
      if (once.groups != null && (once.groups.contains("网站失效")
          || once.groups.contains("校验超时")
          || once.groups.contains(CaptchaInspect.GROUP))) {
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
    // 记录本轮真实执行且命中的步骤（不使用"无失败标记"反推，避免把没跑到的步骤误计成功）
    Set<String> hitSteps = new LinkedHashSet<String>();
    // 启用且有规则可验但链路 URL 缺失或 URL 模板含未解析变量（{{...}}）、整步没跑到的步骤
    // （"搜索"/"发现"/"详情"/"目录"/"正文"）——不静默放过，也不误记失效
    Set<String> uncovered = new LinkedHashSet<String>();
    try {
      groups.addAll(doCheck(source, settings, kind, hitSteps, uncovered));
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      long cost = System.currentTimeMillis() - start;
      // 步骤成功 = 本轮确实跑到且内容命中（hitSteps）；不采用"未失败即成功"的并集拼凑。
      Set<String> succeededSteps = new LinkedHashSet<String>(hitSteps);
      if (groups.contains("校验超时")) {
        return new CheckSourceResult(source, CheckSourceResult.Status.TIMEOUT, groups, "timeout", cost,
            kind, 1, 0, 0, 1, cost, succeededSteps);
      }
      if (groups.isEmpty() && uncovered.isEmpty()) {
        // ResultInspect 是页面启发式：命中的步骤只是"网页探测通过"，ruleToc/ruleContent 并未
        // 按规则真实执行，不能宣称全链路规则可用。
        return new CheckSourceResult(source, CheckSourceResult.Status.SUCCESS, groups,
            "网页探测通过，规则未实测", cost,
            kind, 1, 1, 0, 0, cost, succeededSteps);
      }
      if (groups.isEmpty()) {
        // 无失败但有未覆盖步骤：探测到的步骤全过但证据不全——不算可用也不当失败误删，
        // 保留并标"待复核"（SKIPPED），导出过滤与"仅可用"开关都不会把它当失败剔除。
        List<String> g = new ArrayList<String>();
        for (String s : uncovered) g.add(s + "未覆盖");
        g.add("待复核");
        return new CheckSourceResult(source, CheckSourceResult.Status.SKIPPED, g,
            String.join(",", g), cost, kind, 1, 0, 0, 0, cost, succeededSteps);
      }
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          String.join(",", groups), cost, kind, 1, 0, 1, 0, cost, succeededSteps);
    } catch (Exception e) {
      if (cancelled || Thread.currentThread().isInterrupted()) return null;
      groups.add("网站失效");
      return new CheckSourceResult(source, CheckSourceResult.Status.FAILED, groups,
          e.getMessage() == null ? "error" : e.getMessage(),
          System.currentTimeMillis() - start, kind, 1, 0, 1, 0, 0,
          Collections.<String>emptySet());
    }
  }

  private List<String> doCheck(SourceRecord source, CheckSourceSettings settings, SourceKind kind) {
    return doCheck(source, settings, kind, null, null);
  }

  /** @param hitSteps 非空时，把本轮真实执行且内容命中的步骤名写入（"搜索"/"发现"/"详情"/"目录"/"正文"）。
   *  @param uncovered 非空时，把"启用且有规则可验但链路 URL 缺失或 URL 模板含未解析变量、整步没跑到"的步骤名写入。 */
  private List<String> doCheck(SourceRecord source, CheckSourceSettings settings, SourceKind kind,
                               Set<String> hitSteps, Set<String> uncovered) {
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

    // book/上下文变量：供 AnalyzeUrlLite 替换 {{book.name}}、{{book.bookUrl}} 等。
    // 每步把当前页面 URL 写入 baseUrl；拿到 bookUrl/tocUrl 后写入 book.bookUrl/book.tocUrl。
    Map<String, String> vars = new HashMap<String, String>();
    vars.put("book.name", settings.keyword);
    vars.put("book.origin", source.getUrl());
    vars.put("source.bookSourceUrl", source.getUrl());
    vars.put("baseUrl", base);
    vars.put("source.key", base);

    CaptchaInspect.SiteStatus site = probeSite(base, headers, Math.min(stepTimeout, 10000), settings.keyword);
    if (site == CaptchaInspect.SiteStatus.DEAD) {
      bad.add("网站失效");
      return uniq(bad);
    }
    if (site == CaptchaInspect.SiteStatus.CAPTCHA) {
      bad.add(CaptchaInspect.GROUP);
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
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(searchUrl, base, settings.keyword, 1, vars);
        if (req.usesJs && (req.url == null || req.url.trim().isEmpty() || req.url.equals(base))) {
          bad.add("js失效");
        } else if (req.unresolved) {
          // URL 模板含未解析的 {{...}} 变量（不执行 JS/表达式）：整步没跑到，记未覆盖而非误判失效
          if (uncovered != null) uncovered.add("搜索");
        } else {
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            if (CaptchaInspect.isCaptcha(r.body)) {
              bad.add(CaptchaInspect.GROUP);
            } else {
              ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
              // 内容不正确（被墙/污染）时用系统代理重试一次
              if (!hit.ok) {
                HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
                if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base); }
              }
              if (!stepOk(r, settings) || !hit.ok) bad.add("搜索失效");
              else { if (hitSteps != null) hitSteps.add("搜索");
                     if (!hit.bookUrls.isEmpty()) { bookUrl = hit.bookUrls.get(0); vars.put("book.bookUrl", bookUrl); } }
            }
          } catch (Exception e) {
            bad.add(req.usesJs ? "js失效" : "搜索失效");
          }
        }
      }
    }
    if (bad.contains(CaptchaInspect.GROUP)) return uniq(bad);
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    if (settings.checkDiscovery) {
      String explore = source.rawString("exploreUrl");
      if (explore != null && !explore.trim().isEmpty()) {
        String first = firstExploreUrl(explore);
        AnalyzeUrlLite req = AnalyzeUrlLite.parse(first, base, settings.keyword, 1, vars);
        if (req.usesJs && (req.url == null || req.url.isEmpty())) {
          bad.add("js失效");
        } else if (req.unresolved) {
          if (uncovered != null) uncovered.add("发现");
        } else {
          try {
            HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
            if (CaptchaInspect.isCaptcha(r.body)) {
              bad.add(CaptchaInspect.GROUP);
            } else {
              ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base);
              if (!hit.ok) {
                HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
                if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : base); }
              }
              if (!stepOk(r, settings) || !hit.ok) bad.add("发现失效");
              else { if (hitSteps != null) hitSteps.add("发现");
                     if (bookUrl == null && !hit.bookUrls.isEmpty()) { bookUrl = hit.bookUrls.get(0); vars.put("book.bookUrl", bookUrl); } }
            }
          } catch (Exception e) {
            bad.add(req.usesJs ? "js失效" : "发现失效");
          }
        }
      }
    }
    if (bad.contains(CaptchaInspect.GROUP)) return uniq(bad);
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    Map<String, Object> ruleInfo = source.ruleMap("ruleBookInfo");
    Map<String, Object> ruleToc = source.ruleMap("ruleToc");
    Map<String, Object> ruleContent = source.ruleMap("ruleContent");

    if (settings.checkInfo) {
      if (!ResultInspect.hasRule(ruleInfo) && bookUrl == null) {
        bad.add("详情失效");
      } else if (bookUrl == null) {
        // 有规则但无链路 URL（上游搜索/发现没产出 bookUrl）：整步没跑到，记未覆盖而非静默通过
        if (uncovered != null) uncovered.add("详情");
      } else {
        try {
          if (bookUrl != null) vars.put("book.bookUrl", bookUrl);
          vars.put("baseUrl", bookUrl);
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(bookUrl, base, settings.keyword, 1, vars);
          if (req.unresolved) {
            if (uncovered != null) uncovered.add("详情");
          } else {
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          if (CaptchaInspect.isCaptcha(r.body)) {
            bad.add(CaptchaInspect.GROUP);
            return uniq(bad);
          }
          ResultInspect.Hit hit = ResultInspect.inspectBookPage(r.body);
          if (!hit.ok) {
            HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
            if (r2 != r) { r = r2; hit = ResultInspect.inspectBookPage(r.body); }
          }
          if (!stepOk(r, settings) || !hit.ok) bad.add("详情失效");
          else {
            if (hitSteps != null) hitSteps.add("详情");
            if (!hit.bookUrls.isEmpty()) tocUrl = hit.bookUrls.get(0);
            String infoPageUrl = r.finalUrl != null ? r.finalUrl : bookUrl;
            vars.put("baseUrl", infoPageUrl);
            String initToc = ResultInspect.ruleString(ruleInfo, "tocUrl");
            if (initToc != null && !initToc.trim().isEmpty() && !initToc.contains("@js") && !initToc.contains("<js>")
                && AnalyzeUrlLite.looksLikeUrlTemplate(initToc)) {
              // 仅当 tocUrl 规则像 URL 模板时才拼 URL；.read@href、//a/@href 这类 CSS/XPath
              // 提取规则不在此拼地址（拼出来的是垃圾 URL，会误判目录失效）。
              AnalyzeUrlLite tocReq = AnalyzeUrlLite.parse(initToc, infoPageUrl, settings.keyword, 1, vars);
              if (!tocReq.unresolved) tocUrl = tocReq.url;
              else if (tocUrl == null) tocUrl = infoPageUrl;
            } else if (tocUrl == null) {
              tocUrl = infoPageUrl;
            }
            if (tocUrl != null) vars.put("book.tocUrl", tocUrl);
          }
          }
        } catch (Exception e) {
          bad.add("详情失效");
        }
      }
    }
    if (bad.contains(CaptchaInspect.GROUP)) return uniq(bad);
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    if (settings.checkCategory) {
      if (!ResultInspect.hasRule(ruleToc) && tocUrl == null) {
        bad.add("目录失效");
      } else if (tocUrl == null) {
        if (uncovered != null) uncovered.add("目录");
      } else {
        try {
          vars.put("baseUrl", tocUrl);
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(tocUrl, base, settings.keyword, 1, vars);
          if (req.unresolved) {
            if (uncovered != null) uncovered.add("目录");
          } else {
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          if (CaptchaInspect.isCaptcha(r.body)) {
            bad.add(CaptchaInspect.GROUP);
            return uniq(bad);
          }
          ResultInspect.Hit hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : tocUrl);
          if (!hit.ok) {
            HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
            if (r2 != r) { r = r2; hit = ResultInspect.inspectListPage(r.body, r.finalUrl != null ? r.finalUrl : tocUrl); }
          }
          if (!stepOk(r, settings) || !hit.ok) bad.add("目录失效");
          else { if (hitSteps != null) hitSteps.add("目录");
                 if (!hit.bookUrls.isEmpty()) chapterUrl = hit.bookUrls.get(0); }
          }
        } catch (Exception e) {
          bad.add("目录失效");
        }
      }
    }
    if (bad.contains(CaptchaInspect.GROUP)) return uniq(bad);
    if (Thread.currentThread().isInterrupted() || cancelled) return bad;

    if (settings.checkContent) {
      String contentFail = kind.contentFailGroup();
      if (!ResultInspect.hasRule(ruleContent) && chapterUrl == null) {
        bad.add(contentFail);
      } else if (chapterUrl == null) {
        if (uncovered != null) uncovered.add("正文");
      } else {
        try {
          vars.put("baseUrl", chapterUrl);
          AnalyzeUrlLite req = AnalyzeUrlLite.parse(chapterUrl, base, settings.keyword, 1, vars);
          if (req.unresolved) {
            if (uncovered != null) uncovered.add("正文");
          } else {
          HttpProbe.Response r = HttpProbe.fetch(req, headers, stepTimeout);
          if (CaptchaInspect.isCaptcha(r.body)) {
            bad.add(CaptchaInspect.GROUP);
          } else {
            ResultInspect.Hit hit = ResultInspect.inspectContent(r.body, kind);
            if (!hit.ok) {
              HttpProbe.Response r2 = retryWithProxy(r, req, headers, stepTimeout);
              if (r2 != r) { r = r2; hit = ResultInspect.inspectContent(r.body, kind); }
            }
            if (!stepOk(r, settings) || !hit.ok) bad.add(contentFail);
            else if (hitSteps != null) hitSteps.add("正文");
          }
          }
        } catch (Exception e) {
          bad.add(contentFail);
        }
      }
    }

    return uniq(bad);
  }

  /**
   * 单步 HTTP 判定：状态码落在用户自定义可用区间（isHttpOk），且不是 Cloudflare 挑战页。
   * 内容正确性仍由 ResultInspect 命中判定（调用方据此追加 hit.ok）。
   */
  private static boolean stepOk(HttpProbe.Response r, CheckSourceSettings settings) {
    if (r == null) return false;
    if (!settings.isHttpOk(r.code)) return false;
    return !HttpProbe.isCloudflareChallenge(r.body);
  }

  /** 内容级代理回退：直连返回了可用状态码但内容不正确（被墙/污染），自动用系统代理重试一次。 */
  private static HttpProbe.Response retryWithProxy(HttpProbe.Response r, AnalyzeUrlLite req,
      Map<String, String> headers, int timeoutMs) {
    if (r != null && r.code >= 200 && r.code < 500 && SystemProxy.get() != null) {
      try {
        return HttpProbe.fetch(req, headers, timeoutMs, SystemProxy.get());
      } catch (Exception ignored) {}
    }
    return r;
  }

  /**
   * 合并同域校验结果：对同域名分组，取每个校验步骤的并集。
   * 例如 A1 搜索成功、A2 发现成功 → 合并后该域名搜索+发现都成功。
   */
  public static Map<String, MergeResult> mergeByDomain(List<CheckSourceResult> results) {
    Map<String, List<CheckSourceResult>> byDomain = new LinkedHashMap<String, List<CheckSourceResult>>();
    if (results != null) {
      for (CheckSourceResult r : results) {
        if (r == null || r.status == CheckSourceResult.Status.SKIPPED) continue;
        String domain = extractDomain(r.source.getUrl());
        byDomain.computeIfAbsent(domain, k -> new ArrayList<CheckSourceResult>()).add(r);
      }
    }
    Map<String, MergeResult> merged = new LinkedHashMap<String, MergeResult>();
    for (Map.Entry<String, List<CheckSourceResult>> e : byDomain.entrySet()) {
      Set<String> allSucceeded = new LinkedHashSet<String>();
      for (CheckSourceResult r : e.getValue()) {
        if (r.succeededSteps != null) allSucceeded.addAll(r.succeededSteps);
      }
      merged.put(e.getKey(), new MergeResult(e.getKey(), e.getValue(), allSucceeded));
    }
    return merged;
  }

  public static final class MergeResult {
    public final String domain;
    public final List<CheckSourceResult> sources;
    public final Set<String> mergedSucceededSteps;
    public MergeResult(String domain, List<CheckSourceResult> sources, Set<String> mergedSucceededSteps) {
      this.domain = domain; this.sources = sources; this.mergedSucceededSteps = mergedSucceededSteps;
    }
  }

  private static String extractDomain(String url) {
    try { return new java.net.URL(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); }
    catch (Exception e) { return url; }
  }

  private static List<String> uniq(List<String> bad) {
    List<String> out = new ArrayList<String>();
    if (bad == null) return out;
    for (String g : bad) if (g != null && !out.contains(g)) out.add(g);
    return out;
  }

  private CaptchaInspect.SiteStatus probeSite(String base, Map<String, String> headers, int timeoutMs, String keyword) {
    String host;
    try {
      host = UrlNormalizer.hostKey(base);
    } catch (Exception e) {
      host = base;
    }
    long now = System.currentTimeMillis();
    CachedSite cached = siteCache.get(host);
    if (cached != null && now - cached.at < SITE_CACHE_TTL_MS) return cached.status;
    CaptchaInspect.SiteStatus status;
    try {
      AnalyzeUrlLite baseReq = AnalyzeUrlLite.parse(base, base, keyword, 1);
      HttpProbe.Response br = HttpProbe.fetch(baseReq, headers, timeoutMs);
      status = CaptchaInspect.classify(br.code, br.body);
    } catch (Exception e) {
      status = CaptchaInspect.SiteStatus.DEAD;
    }
    siteCache.put(host, new CachedSite(status, now));
    return status;
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
    final CaptchaInspect.SiteStatus status;
    final long at;
    CachedSite(CaptchaInspect.SiteStatus status, long at) { this.status = status; this.at = at; }
  }

  private static final class AtomicDone {
    private int v;
    synchronized int inc() { return ++v; }
  }
}