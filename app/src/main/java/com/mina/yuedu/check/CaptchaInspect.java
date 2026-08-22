package com.mina.yuedu.check;

import java.util.Locale;

/** Heuristic popup-captcha / challenge page detector. Does not solve captchas. */
public final class CaptchaInspect {
  public static final String GROUP = "弹窗验证码";

  public enum SiteStatus { OK, DEAD, CAPTCHA }

  private CaptchaInspect() {}

  public static boolean hasCaptchaGroup(CheckSourceResult result) {
    return result != null && result.groups != null && result.groups.contains(GROUP);
  }

  public static SiteStatus classify(int code, String body) {
    if (isCaptcha(body)) return SiteStatus.CAPTCHA;
    if (!HttpProbe.httpOk(code, body)) return SiteStatus.DEAD;
    return SiteStatus.OK;
  }

  public static boolean isCaptcha(String body) {
    if (body == null || body.trim().isEmpty()) return false;
    String low = body.toLowerCase(Locale.ROOT);
    if (low.contains("just a moment") && low.contains("cloudflare")) return true;
    if (low.contains("challenge-platform")) return true;
    if (low.contains("cf-turnstile")) return true;
    if (low.contains("geetest") || low.contains("/gt.js")) return true;
    if (low.contains("tencentcaptcha") || low.contains("tcaptcha")) return true;
    if (low.contains("aliyuncaptcha")) return true;
    if (low.contains("google.com/recaptcha") || low.contains("g-recaptcha")) return true;
    if (low.contains("hcaptcha")) return true;
    if (body.contains("请完成安全验证")) return true;
    if (body.contains("滑动验证")) return true;
    if (body.contains("人机验证")) return true;
    if (body.contains("点击完成验证")) return true;
    return false;
  }
}
