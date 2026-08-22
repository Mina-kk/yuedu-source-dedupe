package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.List;

/** Matches 阅读虚拟分组「需要登录」: non-blank loginUrl. loginUi with a form also counts. */
public final class LoginDetector {
  private LoginDetector() {}

  public static boolean needsLogin(SourceRecord source) {
    if (source == null) return false;
    if (hasText(source.rawString("loginUrl"))) return true;
    return hasLoginUi(source.rawString("loginUi"));
  }

  public static int count(List<SourceRecord> records) {
    int n = 0;
    if (records == null) return 0;
    for (SourceRecord s : records) if (needsLogin(s)) n++;
    return n;
  }

  static boolean hasLoginUi(String raw) {
    if (!hasText(raw)) return false;
    String t = raw.trim();
    if ("[]".equals(t) || "{}".equals(t) || "null".equalsIgnoreCase(t)) return false;
    return true;
  }

  static boolean hasText(String s) {
    return s != null && !s.trim().isEmpty();
  }
}
