package com.mina.yuedu.check;

public final class CheckSourceSettings {
  public static final int MAX_CONCURRENCY = 500;
  public static final int DEFAULT_TIMEOUT = 180;
  public static final int DEFAULT_CONCURRENCY = 8;
  public static final int DEFAULT_PROBE_ROUNDS = 10;
  public static final int MAX_PROBE_ROUNDS = 10;
  /** 「快速准确」预设的探测轮数：奇数防平票，3 轮是\"过半通过\"的最小有效轮数。 */
  public static final int QUICK_PROBE_ROUNDS = 3;
  /** 「快速准确」预设的每步超时上限（秒）：网页探测够用即可，避免慢源拖住整轮。 */
  public static final long QUICK_TIMEOUT_SECONDS = 60L;
  public static final String DEFAULT_KEYWORD = "我的";
  public static final String DEFAULT_OK_STATUS = "200-399";

  public long timeoutSeconds = DEFAULT_TIMEOUT;
  public boolean checkSearch = true;
  public boolean checkDiscovery = true;
  public boolean checkInfo = true;
  public boolean checkCategory = true;
  public boolean checkContent = true;
  public String keyword = DEFAULT_KEYWORD;
  public int concurrency = DEFAULT_CONCURRENCY;
  public int probeRounds = DEFAULT_PROBE_ROUNDS;
  public boolean checkNovel = true;
  public boolean checkComic = true;
  public boolean checkVideo = true;
  public boolean checkAudio = true;
  public boolean checkFile = true;
  /** 可用 HTTP 状态码区间，如 "200-399" 或 "200,403,500-502"。 */
  public String okStatusRanges = DEFAULT_OK_STATUS;
  /** 快速模式：只跑搜索+详情两步，跳过发现/目录/正文。 */
  public boolean quickMode = false;
  /** 一键「快速准确」预设：搜索+详情、3 轮探测、超时 60s——网页探测层面快且降低抖动误判。
   *  只改探测参数与步骤开关，绝不动 deleteCaptcha/cleanLogin 等清理语义。 */
  public boolean quickAccurate = false;
  /** 删除弹窗验证码的源（按需排除开关，默认开）。 */
  public boolean deleteCaptcha = true;
  /** 清理需要登录的源（按需排除开关，默认开）。 */
  public boolean cleanLogin = true;

  public void resetToDefaults() {
    timeoutSeconds = DEFAULT_TIMEOUT;
    checkSearch = true;
    checkDiscovery = true;
    checkInfo = true;
    checkCategory = true;
    checkContent = true;
    keyword = DEFAULT_KEYWORD;
    concurrency = DEFAULT_CONCURRENCY;
    probeRounds = DEFAULT_PROBE_ROUNDS;
    checkNovel = true;
    checkComic = true;
    checkVideo = true;
    checkAudio = true;
    checkFile = true;
    okStatusRanges = DEFAULT_OK_STATUS;
    quickMode = false;
    quickAccurate = false;
    deleteCaptcha = true;
    cleanLogin = true;
  }

  /** 套用「快速准确」预设：只改探测参数/步骤开关，清理开关与关键词、状态码区间原样保留。 */
  public void applyQuickAccurate() {
    quickAccurate = true;
    quickMode = true;
    probeRounds = QUICK_PROBE_ROUNDS;
    timeoutSeconds = QUICK_TIMEOUT_SECONDS;
    checkSearch = true;
    checkInfo = true;
    checkDiscovery = false;
    checkCategory = false;
    checkContent = false;
    normalize();
  }

  public void normalize() {
    if (timeoutSeconds < 1) timeoutSeconds = DEFAULT_TIMEOUT;
    if (timeoutSeconds > 300) timeoutSeconds = 300;
    if (!checkSearch && !checkDiscovery) checkSearch = true;
    if (!checkInfo) { checkCategory = false; checkContent = false; }
    if (!checkCategory) checkContent = false;
    // 快速模式：只保留搜索+详情两步
    if (quickMode) { checkDiscovery = false; checkCategory = false; checkContent = false; checkInfo = true; }
    if (concurrency < 1) concurrency = 1;
    if (concurrency > MAX_CONCURRENCY) concurrency = MAX_CONCURRENCY;
    if (probeRounds < 1) probeRounds = 1;
    if (probeRounds > MAX_PROBE_ROUNDS) probeRounds = MAX_PROBE_ROUNDS;
    if (keyword == null || keyword.trim().isEmpty()) keyword = DEFAULT_KEYWORD;
    else keyword = keyword.trim();
    if (!checkNovel && !checkComic && !checkVideo && !checkAudio && !checkFile) {
      checkNovel = true;
      checkComic = true;
      checkVideo = true;
      checkAudio = true;
      checkFile = true;
    }
  }

  public boolean allows(SourceKind kind) {
    if (kind == SourceKind.COMIC) return checkComic;
    if (kind == SourceKind.VIDEO) return checkVideo;
    if (kind == SourceKind.AUDIO) return checkAudio;
    if (kind == SourceKind.FILE) return checkFile;
    return checkNovel;
  }

  /**
   * 判断 HTTP 状态码是否在用户自定义"可达"区间内（如 "200-399" 或 "200,403,500-502"）。
   * 注意：这只是 HTTP 层可达性门槛——即便用户把 4xx/5xx 配进区间，校验引擎仍要求
   * 该步骤内容真实命中（ResultInspect hit.ok）才算步骤成功；自定义状态码不会伪称可用。
   */
  public boolean isHttpOk(int code) {
    String r = okStatusRanges;
    if (r == null || r.trim().isEmpty()) r = DEFAULT_OK_STATUS;
    for (String part : r.split(",")) {
      part = part.trim();
      if (part.isEmpty()) continue;
      int dash = part.indexOf('-');
      if (dash > 0) {
        try {
          int lo = Integer.parseInt(part.substring(0, dash).trim());
          int hi = Integer.parseInt(part.substring(dash + 1).trim());
          if (code >= lo && code <= hi) return true;
        } catch (NumberFormatException ignored) {}
      } else {
        try { if (Integer.parseInt(part) == code) return true; }
        catch (NumberFormatException ignored) {}
      }
    }
    return false;
  }

  public long timeoutMillis() { return timeoutSeconds * 1000L; }

  public CheckSourceSettings copy() {
    CheckSourceSettings s = new CheckSourceSettings();
    s.timeoutSeconds = timeoutSeconds;
    s.checkSearch = checkSearch;
    s.checkDiscovery = checkDiscovery;
    s.checkInfo = checkInfo;
    s.checkCategory = checkCategory;
    s.checkContent = checkContent;
    s.keyword = keyword;
    s.concurrency = concurrency;
    s.probeRounds = probeRounds;
    s.checkNovel = checkNovel;
    s.checkComic = checkComic;
    s.checkVideo = checkVideo;
    s.checkAudio = checkAudio;
    s.checkFile = checkFile;
    s.okStatusRanges = okStatusRanges;
    s.quickMode = quickMode;
    s.quickAccurate = quickAccurate;
    s.deleteCaptcha = deleteCaptcha;
    s.cleanLogin = cleanLogin;
    return s;
  }
}