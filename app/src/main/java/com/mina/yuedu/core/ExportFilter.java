package com.mina.yuedu.core;

import com.mina.yuedu.check.CaptchaInspect;
import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filters the export/import list. Does not mutate retained in-memory records. */
public final class ExportFilter {
  private ExportFilter() {}

  public static List<SourceRecord> apply(
      List<SourceRecord> retained,
      List<CheckSourceResult> checks,
      boolean cleanLogin,
      boolean onlyUsable,
      boolean deleteCaptcha,
      Set<SourceKind> allow) {
    List<SourceRecord> out = new ArrayList<SourceRecord>();
    if (retained == null) return out;
    if (allow != null && allow.isEmpty()) return out;
    Map<String, CheckSourceResult> byUrl = index(checks);
    boolean hasChecks = checks != null && !checks.isEmpty();
    for (SourceRecord source : retained) {
      if (source == null) continue;
      if (cleanLogin && LoginDetector.needsLogin(source)) continue;
      CheckSourceResult check = find(byUrl, source);
      if (deleteCaptcha && hasChecks && CaptchaInspect.hasCaptchaGroup(check)) continue;
      if (onlyUsable && hasChecks && (check == null || !check.isUsable())) continue;
      if (allow != null && !allow.contains(SourceKind.of(source))) continue;
      out.add(source);
    }
    return out;
  }

  public static int captchaCount(List<CheckSourceResult> checks) {
    int n = 0;
    if (checks == null) return 0;
    for (CheckSourceResult r : checks) if (CaptchaInspect.hasCaptchaGroup(r)) n++;
    return n;
  }

  private static Map<String, CheckSourceResult> index(List<CheckSourceResult> checks) {
    Map<String, CheckSourceResult> map = new HashMap<String, CheckSourceResult>();
    if (checks == null) return map;
    for (CheckSourceResult r : checks) {
      if (r == null || r.source == null || r.source.getUrl() == null) continue;
      map.put(r.source.getUrl(), r);
    }
    return map;
  }

  private static CheckSourceResult find(Map<String, CheckSourceResult> byUrl, SourceRecord source) {
    if (source.getUrl() == null) return null;
    return byUrl.get(source.getUrl());
  }
}
