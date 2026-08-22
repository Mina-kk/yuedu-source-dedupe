package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CaptchaInspectTest {
  @Test public void cloudflareAndTurnstileAreCaptcha() {
    assertTrue(CaptchaInspect.isCaptcha("<html>Just a moment... Cloudflare</html>"));
    assertTrue(CaptchaInspect.isCaptcha("<div id=\"challenge-platform\"></div>"));
    assertTrue(CaptchaInspect.isCaptcha("<div class=\"cf-turnstile\"></div>"));
  }

  @Test public void chineseAndVendorWidgetsAreCaptcha() {
    assertTrue(CaptchaInspect.isCaptcha("请完成安全验证后再继续"));
    assertTrue(CaptchaInspect.isCaptcha("请进行滑动验证"));
    assertTrue(CaptchaInspect.isCaptcha("人机验证"));
    assertTrue(CaptchaInspect.isCaptcha("<script src=\"https://static.geetest.com/static/js/gt.js\"></script>"));
    assertTrue(CaptchaInspect.isCaptcha("TencentCaptcha"));
    assertTrue(CaptchaInspect.isCaptcha("https://www.google.com/recaptcha/api.js"));
    assertTrue(CaptchaInspect.isCaptcha("hcaptcha.com/1/api.js"));
  }

  @Test public void normalListPagesAreNotCaptcha() {
    assertFalse(CaptchaInspect.isCaptcha("<html><a href=\"/book/1\">斗破苍穹</a></html>"));
    assertFalse(CaptchaInspect.isCaptcha("{\"list\":[{\"name\":\"书\",\"bookUrl\":\"https://a.example/1\"}]}"));
    assertFalse(CaptchaInspect.isCaptcha("<button>获取验证码</button>"));
    assertFalse(CaptchaInspect.isCaptcha(""));
    assertFalse(CaptchaInspect.isCaptcha(null));
  }

  @Test public void classifyPrefersCaptchaOverDeadSite() {
    assertEquals(CaptchaInspect.SiteStatus.CAPTCHA,
        CaptchaInspect.classify(200, "<html>Just a moment... Cloudflare</html>"));
    assertEquals(CaptchaInspect.SiteStatus.DEAD, CaptchaInspect.classify(404, "not found"));
    assertEquals(CaptchaInspect.SiteStatus.OK, CaptchaInspect.classify(200, "<html>ok</html>"));
  }
}
