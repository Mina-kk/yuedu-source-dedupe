package com.mina.yuedu.core;

import com.mina.yuedu.check.CaptchaInspect;
import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.SourceRecord;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExportFilterTest {
  @Test public void dropsLoginWhenCleanLoginIsOn() {
    SourceRecord login = src("登录源", 0, "https://a.example/login");
    SourceRecord plain = src("普通源", 0, "https://b.example");
    List<SourceRecord> out = ExportFilter.apply(
        Arrays.asList(login, plain), Collections.<CheckSourceResult>emptyList(),
        true, false, true, EnumSet.of(SourceKind.NOVEL));
    assertEquals(1, out.size());
    assertEquals("普通源", out.get(0).getName());
  }

  @Test public void keepsLoginWhenCleanLoginIsOff() {
    SourceRecord login = src("登录源", 0, "https://a.example/login");
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(login), Collections.<CheckSourceResult>emptyList(),
        false, false, true, EnumSet.of(SourceKind.NOVEL));
    assertEquals(1, out.size());
  }

  @Test public void dropsCaptchaWhenDeleteIsOnEvenIfOnlyUsableIsOff() {
    SourceRecord captcha = src("盾源", 0, "https://c.example");
    SourceRecord ok = src("好源", 0, "https://d.example");
    CheckSourceResult cap = new CheckSourceResult(captcha, CheckSourceResult.Status.FAILED,
        Collections.singletonList(CaptchaInspect.GROUP), "captcha", 10);
    CheckSourceResult good = new CheckSourceResult(ok, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Arrays.asList(captcha, ok), Arrays.asList(cap, good),
        false, false, true, EnumSet.of(SourceKind.NOVEL));
    assertEquals(1, out.size());
    assertEquals("好源", out.get(0).getName());
  }

  @Test public void keepsCaptchaWhenDeleteIsOffAndOnlyUsableIsOff() {
    SourceRecord captcha = src("盾源", 0, "https://c.example");
    CheckSourceResult cap = new CheckSourceResult(captcha, CheckSourceResult.Status.FAILED,
        Collections.singletonList(CaptchaInspect.GROUP), "captcha", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(captcha), Collections.singletonList(cap),
        false, false, false, EnumSet.of(SourceKind.NOVEL));
    assertEquals(1, out.size());
  }

  @Test public void emptyKindSetExportsNothing() {
    SourceRecord novel = src("普通源", 0, "https://b.example");
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(novel), Collections.<CheckSourceResult>emptyList(),
        true, false, true, Collections.<SourceKind>emptySet());
    assertEquals(0, out.size());
  }

  private static SourceRecord src(String name, int type, String extra) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", "https://example.com/" + name);
    raw.put("bookSourceType", type);
    if (extra != null && extra.contains("login")) raw.put("loginUrl", extra);
    return new SourceRecord(0, name, "https://example.com/" + name, raw);
  }
}
