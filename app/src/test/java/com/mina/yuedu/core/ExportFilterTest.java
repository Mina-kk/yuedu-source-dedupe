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

  // ===== 误删/误判回归 =====

  @Test public void sameUrlDifferentSourcesKeepSeparateResults() {
    // 两份不同源同 URL：URL-key 冲突时后者结果不能覆盖前者。
    SourceRecord a = new SourceRecord(0, "源A", "https://same.example", rawWith("x", "a"));
    SourceRecord b = new SourceRecord(1, "源B", "https://same.example", rawWith("x", "b")); // 同 URL
    CheckSourceResult failA = new CheckSourceResult(a, CheckSourceResult.Status.FAILED,
        Collections.singletonList("搜索失效"), "fail", 10);
    CheckSourceResult okB = new CheckSourceResult(b, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Arrays.asList(a, b), Arrays.asList(failA, okB),
        false, true, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable
    // a 实测失败应被 onlyUsable 滤掉，b 可用保留 → 各按各的结果判定，不串扰
    assertEquals(1, out.size());
    assertEquals("源B", out.get(0).getName());
  }

  @Test public void declaredCaptchaKeptWhenCheckSucceeds() {
    // 声明含验证码字段但实测可用 → 不删（静态字段是推断不是证据）。
    SourceRecord declared = new SourceRecord(0, "声明源", "https://e.example", rawWith("authUrl", "https://e.example/oauth"));
    CheckSourceResult ok = new CheckSourceResult(declared, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(declared), Collections.singletonList(ok),
        false, false, true, EnumSet.of(SourceKind.NOVEL));
    assertEquals(1, out.size());
  }

  @Test public void declaredCaptchaKeptEvenWhenGenericFail() {
    // 声明含验证码字段 + 实测 generic FAILED（非验证码组）：静态字段不升级删除依据，
    // 普通网络失败不能错归验证码 → 保留。
    SourceRecord declared = new SourceRecord(0, "声明源", "https://e.example", rawWith("authUrl", "https://e.example/oauth"));
    CheckSourceResult fail = new CheckSourceResult(declared, CheckSourceResult.Status.FAILED,
        Collections.singletonList("搜索失效"), "fail", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(declared), Collections.singletonList(fail),
        false, false, true, EnumSet.of(SourceKind.NOVEL));
    assertEquals("静态 authUrl 声明+generic FAILED 不应误删", 1, out.size());
  }

  @Test public void realCaptchaGroupStillDeletes() {
    // 实测命中验证码分组 → 仍然删除（真实证据）。
    SourceRecord captcha = src("盾源", 0, null);
    CheckSourceResult cap = new CheckSourceResult(captcha, CheckSourceResult.Status.FAILED,
        Collections.singletonList(CaptchaInspect.GROUP), "captcha", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(captcha), Collections.singletonList(cap),
        false, false, true, EnumSet.of(SourceKind.NOVEL));
    assertEquals(0, out.size());
  }

  @Test public void declaredLoginKeptWithoutCheckEvidence() {
    // 未跑校验（hasChecks=false）：静态 loginCheckUrl 声明不单独作删除依据。
    SourceRecord declared = new SourceRecord(0, "声明登录源", "https://f.example", rawWith("loginCheckUrl", "https://f.example/chk"));
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(declared), Collections.<CheckSourceResult>emptyList(),
        true, false, false, EnumSet.of(SourceKind.NOVEL));
    assertEquals(1, out.size());
  }

  @Test public void ambiguousSameUrlNoFallbackGuesses() {
    // 同 URL 多条结果：URL 兜底不猜，只认实例匹配。a 无实例匹配且 URL 多义 → 无证据。
    // onlyUsable 开启时 a 无结果被排除；b 实例匹配可用保留。
    SourceRecord a = new SourceRecord(0, "源A", "https://same.example", rawWith("x", "a"));
    SourceRecord b = new SourceRecord(1, "源B", "https://same.example", rawWith("x", "b"));
    SourceRecord aShadow = new SourceRecord(0, "源A", "https://same.example", rawWith("x", "a")); // 另一实例同 URL
    CheckSourceResult failShadow = new CheckSourceResult(aShadow, CheckSourceResult.Status.FAILED,
        Collections.singletonList("搜索失效"), "fail", 10);
    CheckSourceResult okB = new CheckSourceResult(b, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    // checks 里 failShadow 和 okB 同 URL → URL 多义，b 靠实例命中，a 无证据
    List<SourceRecord> out = ExportFilter.apply(
        Arrays.asList(a, b), Arrays.asList(failShadow, okB),
        false, true, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable
    assertEquals(1, out.size());
    assertEquals("源B", out.get(0).getName());
  }

  @Test public void uniqueUrlFallsBackWhenNoInstanceMatch() {
    // 同 URL 但实例不同（去重合并换了实例）：内容指纹确证相同（URL+名称+类型+规则字段一致）
    // → 指纹兜底命中唯一结果。
    SourceRecord retained = new SourceRecord(5, "同源", "https://uniq.example", rawWith("x", "1"));
    SourceRecord checked = new SourceRecord(0, "同源", "https://uniq.example", rawWith("x", "1")); // 同 URL+同内容指纹
    CheckSourceResult ok = new CheckSourceResult(checked, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(retained), Collections.singletonList(ok),
        false, true, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable：指纹唯一兜底命中 → 保留
    assertEquals(1, out.size());
  }

  @Test public void differentRulesSameUrlNoFingerprintMatch() {
    // 同 URL 但规则字段不同 → 指纹不同，不能套用对方校验结果；onlyUsable 下无证据排除。
    SourceRecord retained = new SourceRecord(5, "保留源", "https://uniq.example", rawWith("ruleSearch", "rule-a"));
    SourceRecord checked = new SourceRecord(0, "保留源", "https://uniq.example", rawWith("ruleSearch", "rule-b")); // 同 URL 不同规则
    CheckSourceResult ok = new CheckSourceResult(checked, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(retained), Collections.singletonList(ok),
        false, true, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable：指纹不同不误配 → 排除
    assertEquals("规则字段不同不得套用校验结果", 0, out.size());
  }

  @Test public void externalFieldDiffBreaksFingerprint() {
    // 完整指纹覆盖全部 raw 字段：bookSourceGroup 不同即不同源，不套用对方校验结果。
    SourceRecord retained = new SourceRecord(5, "同源", "https://uniq.example",
        rawMulti(new String[]{"ruleSearch", "bookSourceGroup"}, new Object[]{"r1", "分组B"}));
    SourceRecord checked = new SourceRecord(0, "同源", "https://uniq.example",
        rawMulti(new String[]{"ruleSearch", "bookSourceGroup"}, new Object[]{"r1", "分组A"}));
    CheckSourceResult ok = new CheckSourceResult(checked, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(retained), Collections.singletonList(ok),
        false, true, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable：指纹不同不误配 → 排除
    assertEquals("分组字段不同应破坏指纹，不套用校验证据", 0, out.size());
  }

  @Test public void differentEnabledBreaksFingerprint() {
    // 同 URL 同规则但 enabled 不同（一个禁用）→ 不能算同一份源，不套用证据。
    SourceRecord retained = new SourceRecord(5, "同源", "https://uniq.example",
        rawMulti(new String[]{"ruleSearch", "enabled"}, new Object[]{"r1", Boolean.FALSE}));
    SourceRecord checked = new SourceRecord(0, "同源", "https://uniq.example",
        rawMulti(new String[]{"ruleSearch", "enabled"}, new Object[]{"r1", Boolean.TRUE}));
    CheckSourceResult ok = new CheckSourceResult(checked, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(retained), Collections.singletonList(ok),
        false, true, false, EnumSet.of(SourceKind.NOVEL));
    assertEquals("enabled 不同不应套用校验证据", 0, out.size());
  }

  @Test public void identicalRawFingerprintStillMatches() {
    // 完整 raw 完全相等（含全部字段）→ 确证同一份源，实例不同也兜底命中。
    Map<String, Object> rawA = rawMulti(new String[]{"ruleSearch", "enabled", "bookSourceGroup"},
        new Object[]{"r1", Boolean.TRUE, "分组A"});
    SourceRecord retained = new SourceRecord(5, "同源", "https://uniq.example", rawA);
    SourceRecord checked = new SourceRecord(0, "同源", "https://uniq.example",
        rawMulti(new String[]{"ruleSearch", "enabled", "bookSourceGroup"},
            new Object[]{"r1", Boolean.TRUE, "分组A"}));  // 另一实例，raw 完全相等
    CheckSourceResult ok = new CheckSourceResult(checked, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(retained), Collections.singletonList(ok),
        false, true, false, EnumSet.of(SourceKind.NOVEL));
    assertEquals("完整raw相等应命中", 1, out.size());
  }

  @Test public void reviewSkippedSourceKeptWhenOnlyUsableOff() {
    // 待复核源（SKIPPED：探测步骤全过但部分启用步骤未覆盖）证据不足 →
    // 不当失败误删：onlyUsable 关时与其他源一样保留导出。
    SourceRecord review = new SourceRecord(0, "待复核源", "https://r.example", rawWith("x", "1"));
    CheckSourceResult skipped = new CheckSourceResult(review, CheckSourceResult.Status.SKIPPED,
        java.util.Arrays.asList("目录未覆盖", "待复核"), "uncovered", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(review), Collections.singletonList(skipped),
        false, false, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable 关
    assertEquals("证据不足的待复核源不应被误删", 1, out.size());
  }

  @Test public void reviewSkippedExcludedOnlyByExplicitOnlyUsable() {
    // onlyUsable 开 = 用户显式只看"探测通过"证据：待复核源非 SUCCESS 被过滤——
    // 这是用户主动过滤选择，与 FAILED 同等对待，不算后台误删。
    SourceRecord review = new SourceRecord(0, "待复核源", "https://r.example", rawWith("x", "1"));
    CheckSourceResult skipped = new CheckSourceResult(review, CheckSourceResult.Status.SKIPPED,
        java.util.Arrays.asList("目录未覆盖", "待复核"), "uncovered", 10);
    List<SourceRecord> out = ExportFilter.apply(
        Collections.singletonList(review), Collections.singletonList(skipped),
        false, true, false, EnumSet.of(SourceKind.NOVEL)); // onlyUsable 开
    assertEquals("显式'仅探测通过'下待复核源被过滤属预期", 0, out.size());
  }

  private static Map<String, Object> rawWith(String k, Object v) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", "x");
    raw.put("bookSourceUrl", "https://x.example");
    raw.put(k, v);
    return raw;
  }

  private static Map<String, Object> rawMulti(String[] keys, Object[] values) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", "x");
    raw.put("bookSourceUrl", "https://x.example");
    for (int i = 0; i < keys.length; i++) raw.put(keys[i], values[i]);
    return raw;
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
