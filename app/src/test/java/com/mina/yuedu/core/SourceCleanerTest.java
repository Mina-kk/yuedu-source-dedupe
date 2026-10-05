package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 验证 SourceCleaner 过滤逻辑：cleanLogin + cleanCaptcha（移植自 MIXUULS，构造本地 SourceRecord）。 */
public class SourceCleanerTest {

  private static SourceRecord make(int order, String name, String url, Map<String, Object> extra) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    if (extra != null) raw.putAll(extra);
    return new SourceRecord(order, name, url, raw);
  }

  private static SourceRecord normal() { return make(0, "正常源", "https://example.com", null); }

  private static SourceRecord with(String key, Object value) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(key, value);
    return make(0, "测试源", "https://example.com", extra);
  }

  @Test public void normalSourceNeedsLoginFalse() {
    assertFalse(SourceCleaner.needsLogin(normal()));
  }

  @Test public void loginSourceNeedsLoginTrue() {
    assertTrue(SourceCleaner.needsLogin(with("loginUrl", "https://example.com/login")));
  }

  @Test public void loginCheckSourceNeedsLoginTrue() {
    assertTrue(SourceCleaner.needsLogin(with("loginCheckUrl", "https://example.com/check")));
  }

  @Test public void normalSourceHasCaptchaFalse() {
    assertFalse(SourceCleaner.hasCaptcha(normal()));
  }

  @Test public void captchaSourceHasCaptchaTrue() {
    assertTrue(SourceCleaner.hasCaptcha(with("captchaUrl", "https://example.com/captcha")));
  }

  @Test public void verifyCodeSourceHasCaptchaTrue() {
    assertTrue(SourceCleaner.hasCaptcha(with("verifyCode", "some-rule")));
  }

  @Test public void authUrlSourceHasCaptchaTrue() {
    assertTrue(SourceCleaner.hasCaptcha(with("authUrl", "https://example.com/auth")));
  }

  @Test public void cleanLoginFiltersLoginSources() {
    List<SourceRecord> all = Arrays.asList(
        normal(),
        with("loginUrl", "https://example.com/login"),
        normal(),
        with("loginCheckUrl", "https://example.com/check")
    );
    List<SourceRecord> result = SourceCleaner.cleanLogin(all);
    assertEquals(2, result.size());
    for (SourceRecord s : result) {
      assertFalse(SourceCleaner.needsLogin(s));
    }
  }

  @Test public void cleanCaptchaFiltersCaptchaSources() {
    List<SourceRecord> all = Arrays.asList(
        normal(),
        with("captchaUrl", "https://example.com/captcha"),
        with("verifyCode", "rule"),
        with("authUrl", "https://example.com/auth")
    );
    List<SourceRecord> result = SourceCleaner.cleanCaptcha(all);
    assertEquals(1, result.size());
    assertFalse(SourceCleaner.hasCaptcha(result.get(0)));
  }

  @Test public void cleanLoginNullHandling() {
    assertTrue(SourceCleaner.cleanLogin(null).isEmpty());
  }

  @Test public void cleanCaptchaNullHandling() {
    assertTrue(SourceCleaner.cleanCaptcha(null).isEmpty());
  }

  @Test public void blankLoginAndCaptchaFieldsDoNotCauseFalseFiltering() {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("loginUrl", " ");
    extra.put("captchaUrl", "");
    SourceRecord source = make(0, "正常源", "https://example.com", extra);

    assertFalse(SourceCleaner.needsLogin(source));
    assertFalse(SourceCleaner.hasCaptcha(source));
    assertEquals(1, SourceCleaner.cleanLogin(Arrays.asList(source)).size());
    assertEquals(1, SourceCleaner.cleanCaptcha(Arrays.asList(source)).size());
  }
}
