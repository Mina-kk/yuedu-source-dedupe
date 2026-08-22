package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoginDetectorTest {
  @Test public void loginUrlMatchesReadingNeedLogin() {
    assertTrue(LoginDetector.needsLogin(src("loginUrl", "https://a.example/login")));
    assertTrue(LoginDetector.needsLogin(src("loginUrl", "  js  ")));
    assertFalse(LoginDetector.needsLogin(src("loginUrl", "")));
    assertFalse(LoginDetector.needsLogin(src("loginUrl", "   ")));
  }

  @Test public void loginUiCountsUnlessEmptyJson() {
    assertTrue(LoginDetector.needsLogin(src("loginUi", "[{\"name\":\"password\",\"type\":\"password\"}]")));
    assertFalse(LoginDetector.needsLogin(src("loginUi", "[]")));
    assertFalse(LoginDetector.needsLogin(src("loginUi", "{}")));
    assertFalse(LoginDetector.needsLogin(src("loginUi", "null")));
  }

  @Test public void loginCheckJsAloneIsNotLogin() {
    assertFalse(LoginDetector.needsLogin(src("loginCheckJs", "result;")));
  }

  @Test public void countLoginSources() {
    SourceRecord login = src("loginUrl", "https://a.example/login");
    SourceRecord plain = src("bookSourceComment", "普通源");
    assertEquals(1, LoginDetector.count(Arrays.asList(login, plain)));
    assertEquals(0, LoginDetector.count(null));
  }

  private static SourceRecord src(String key, String value) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", "源");
    raw.put("bookSourceUrl", "https://example.com");
    raw.put(key, value);
    return new SourceRecord(0, "源", "https://example.com", raw);
  }
}
