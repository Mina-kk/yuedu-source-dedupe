package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书源字段级过滤工具（提取自 MIXUULS/yuedu-source-dedupe）：
 * 按书源声明字段清理「需要登录」/「弹窗验证码」的源。
 *
 * 与运行时的 LoginDetector（识别 loginUrl/loginUi 表单）和
 * CaptchaInspect（识别真实弹窗内容）互补：这里基于书源 JSON 中
 * 静态字段的存在与否做按需排除，适合在不发网络校验时预先剔除。
 *
 * 纯逻辑，不依赖 Android，可单元测试。
 */
public final class SourceCleaner {

  private SourceCleaner() {}

  /**
   * 静态字段推断「需要登录」：loginUrl 或 loginCheckUrl 有值。
   * 仅为字段声明级推断（可能误报：历史遗留字段、失效字段），删除决策应结合实测。
   */
  public static boolean needsLogin(SourceRecord s) {
    if (s == null) return false;
    Map<String, Object> raw = s.getRaw();
    return hasValue(raw, "loginUrl") || hasValue(raw, "loginCheckUrl");
  }

  /**
   * 静态字段推断「有弹窗验证码」：captchaUrl、verifyCode 或 authUrl 有值。
   * 仅为字段声明级推断（authUrl/verifyCode 可能是 OAuth/Token 刷新等非验证码用途），
   * 删除决策应结合实测。
   */
  public static boolean hasCaptcha(SourceRecord s) {
    if (s == null) return false;
    Map<String, Object> raw = s.getRaw();
    return hasValue(raw, "captchaUrl") || hasValue(raw, "verifyCode") || hasValue(raw, "authUrl");
  }

  /** 字段级登录提示（同 needsLogin，语义标注为"声明推断"）。 */
  public static boolean declaredLogin(SourceRecord s) { return needsLogin(s); }

  /** 字段级验证码提示（同 hasCaptcha，语义标注为"声明推断"）。 */
  public static boolean declaredCaptcha(SourceRecord s) { return hasCaptcha(s); }

  /** 空字段是书源导出时常见的占位值，不能因此过滤掉书源。 */
  private static boolean hasValue(Map<String, Object> raw, String key) {
    Object value = raw.get(key);
    return value != null && !String.valueOf(value).trim().isEmpty();
  }

  /** 过滤掉需要登录的源。 */
  public static List<SourceRecord> cleanLogin(List<SourceRecord> sources) {
    if (sources == null) return new ArrayList<>();
    List<SourceRecord> out = new ArrayList<>();
    for (SourceRecord s : sources) {
      if (!needsLogin(s)) out.add(s);
    }
    return out;
  }

  /** 过滤掉有弹窗验证码的源。 */
  public static List<SourceRecord> cleanCaptcha(List<SourceRecord> sources) {
    if (sources == null) return new ArrayList<>();
    List<SourceRecord> out = new ArrayList<>();
    for (SourceRecord s : sources) {
      if (!hasCaptcha(s)) out.add(s);
    }
    return out;
  }
}
