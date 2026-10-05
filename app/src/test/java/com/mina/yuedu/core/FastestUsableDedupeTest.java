package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FastestUsableDedupeTest {
  @Test public void sameHostDifferentIdentityNeverRemovedEvenWhenSlower() {
    // 同域但 URL/名称/raw 不同（path、#标签、规则任一不同）→ 非完全身份等价，
    // 即便慢且确认探测稳定更快也绝不删除，仅进"耗时接近，未淘汰"参考组。
    SourceRecord fast = src(0, "快", "https://a.com", 0);
    SourceRecord slow = src(1, "慢", "https://www.a.com/path#🎃", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 120), ok(slow, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, (source) -> source == fast ? ok(fast, 110) : ok(slow, 900));
    assertEquals("规则/路径/名称不同的同域源不许因速度误删", 2, out.retained.size());
    assertEquals(0, out.removedSlow);
    assertEquals(1, out.closeKept);
    // 保留的 close 源不进 DuplicateGroup → duplicate 计数不失真
    assertEquals(0, out.groups.size());
  }

  @Test public void fullIdentityDuplicateCanStillBeRemovedBySpeed() {
    // URL+名称+raw 完全相等（前序去重认定的同一源等价副本）才允许速度淘汰。
    SourceRecord fast = src(0, "同源", "https://a.com", 0);
    SourceRecord slow = src(1, "同源", "https://a.com", 0); // raw 相同
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 100), ok(slow, 900));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, null);
    assertEquals("完全身份等价才可因速度淘汰", 1, out.retained.size());
    assertEquals(1, out.removedSlow);
    assertTrue(out.groups.get(0).getReason().contains("同类型同域名仅保留最快可用"));
  }

  @Test public void quickAccurateModeNeverRemovesOnlyRanks() {
    // 快速准确（speedDedupe=false）：即便同域同速差且身份不等价，也一个不删，
    // 全部保留为"耗时接近，未淘汰"参考组——纯排序参考，零误删。
    SourceRecord a = src(0, "A", "https://a.com", 0);
    SourceRecord b = src(1, "B", "https://a.com/x", 0);
    SourceRecord c = src(2, "C", "https://a.com/y", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(a, 50), ok(b, 500), ok(c, 900));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(a, b, c), checks, 3, null, false);
    assertEquals(3, out.retained.size());
    assertEquals(0, out.removedSlow);
    assertEquals(2, out.closeKept);
    // 排序参考：最快的排前
    assertEquals("A", out.retained.get(0).getName());
  }

  @Test public void sameUrlDifferentRulesDoNotShareCheckEvidence() {
    // 同 URL 不同规则的两条源：fingerprint 不同 → 不套用对方校验结果，
    // 规则不同的那条无证据 → 进 skipped 保留，不被别人的 SUCCESS 带上"可用"光环。
    SourceRecord a = new SourceRecord(0, "源A", "https://same.example",
        rawWith("ruleSearch", "rule-a"));
    SourceRecord b = new SourceRecord(1, "源B", "https://same.example",
        rawWith("ruleSearch", "rule-b"));
    CheckSourceResult okB = new CheckSourceResult(b, CheckSourceResult.Status.SUCCESS,
        Collections.<String>emptyList(), "ok", 10, SourceKind.NOVEL, 3, 3, 0, 0, 10);
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(a, b), Collections.singletonList(okB), 3, null, true);
    // a 无证据 → skipped 保留；b 可用保留；a 不算可用不参与竞争
    assertEquals(2, out.retained.size());
    assertEquals(1, out.skipped);
  }

  @Test public void uncoveredSkippedSourceExportedWhenOnlyUsableOff() {
    // 未覆盖待复核源：同域最快排序不参与竞争、保留在结果里——
    // 与 ExportFilter onlyUsable=off 一致不被误删。
    SourceRecord usable = src(0, "可用", "https://a.com", 0);
    SourceRecord review = src(1, "待复核", "https://a.com/other", 0);
    List<CheckSourceResult> checks = Arrays.asList(
        ok(usable, 80),
        new CheckSourceResult(review, CheckSourceResult.Status.SKIPPED,
            Arrays.asList("目录未覆盖", "待复核"), "uncovered", 200, SourceKind.NOVEL, 3, 0, 0, 0, 200));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(usable, review), checks, 3, null, true);
    assertEquals(2, out.retained.size());
    assertEquals(1, out.skipped);
    assertEquals(0, out.removedSlow);
  }

  @Test public void closeTimesAreBothKeptWithoutPhantomDuplicateGroup() {
    // 耗时接近的同域源全部保留，且不再为"保留"建 DuplicateGroup——
    // 否则 getDuplicateCount() 会把 retained 里的源错计成重复删除。
    SourceRecord a = src(0, "A", "https://a.com", 0);
    SourceRecord b = src(1, "B", "https://a.com/other", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(a, 200), ok(b, 210));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(Arrays.asList(a, b), checks, 10, null);
    assertEquals(2, out.retained.size());
    assertEquals(1, out.closeKept);
    assertEquals("保留的 close 源不得产生重复组", 0, out.groups.size());
  }

  @Test public void novelAndComicOnSameHostAreNotMerged() {
    SourceRecord novel = src(0, "小说", "https://a.com", 0);
    SourceRecord comic = src(1, "漫画", "https://a.com", 2);
    List<CheckSourceResult> checks = Arrays.asList(ok(novel, 100), ok(comic, 900));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(Arrays.asList(novel, comic), checks, 10, null);
    assertEquals(2, out.retained.size());
    assertEquals(0, out.removedSlow);
  }

  @Test public void unusableDoesNotCompeteForFastest() {
    SourceRecord good = src(0, "可用", "https://a.com", 0);
    SourceRecord bad = src(1, "失败", "https://www.a.com", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(good, 100), fail(bad));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(Arrays.asList(good, bad), checks, 10, null);
    assertEquals(2, out.retained.size());
    assertEquals(0, out.removedSlow);
  }

  @Test public void identityTagsStillDistinctBeforeCheck() {
    DedupeResult result = DedupeEngine.run(Arrays.asList(
        src(0, "原站", "https://example.com", 0),
        src(1, "简体", "https://example.com#简体", 0)), DedupeMode.STANDARD, false);
    assertEquals(2, result.getRetained().size());
  }

  @Test public void wwwAndTaggedUrlShareHostKey() throws Exception {
    assertEquals(UrlNormalizer.hostKey("https://a.com"), UrlNormalizer.hostKey("https://www.a.com/path#🎃"));
    SourceRecord a = src(0, "A", "https://a.com", 0);
    SourceRecord b = src(1, "B", "https://www.a.com/path#🎃", 0);
    assertEquals(FastestUsableDedupe.bucketKey(a, SourceKind.NOVEL),
        FastestUsableDedupe.bucketKey(b, SourceKind.NOVEL));
  }

  @Test public void jitterConfirmKeepsBothWhenProbeIsInconclusive() {
    SourceRecord a = src(0, "A", "https://a.com", 0);
    SourceRecord b = src(1, "B", "https://a.com/x", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(a, 100), ok(b, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(a, b), checks, 10, source -> ok(source, 150));
    assertEquals(2, out.retained.size());
    assertEquals(0, out.removedSlow);
    assertFalse(FastestUsableDedupe.isClose(100, 800));
  }

  @Test public void cancelledConfirmProbeDoesNotDropSlowSource() {
    SourceRecord fast = src(0, "快", "https://a.com", 0);
    SourceRecord slow = src(1, "慢", "https://www.a.com/path", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 100), ok(slow, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, source -> null);
    assertEquals(2, out.retained.size());
    assertEquals(0, out.removedSlow);
    assertEquals(1, out.closeKept);
  }

  @Test public void medianOnlyFinishDoesNotNeedExtraHttp() {
    // 完全身份等价副本（URL+名称+raw 全同）+ 中位明显更快 → 无需额外探测即可淘汰。
    SourceRecord fast = src(0, "同源", "https://a.com", 0);
    SourceRecord slow = src(1, "同源", "https://a.com", 0); // raw 相同 = 等价副本
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 120), ok(slow, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, null);
    assertEquals(1, out.retained.size());
    assertEquals("同源", out.retained.get(0).getName());
    assertEquals(1, out.removedSlow);
  }

  @Test public void differentPathSameHostNeverRemovedBySpeed() {
    // 同域不同 path/名称/规则 → 非等价身份，绝不因速度误删（收紧后回归）。
    SourceRecord fast = src(0, "快", "https://a.com", 0);
    SourceRecord slow = src(1, "慢", "https://www.a.com/path", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 120), ok(slow, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, null);
    assertEquals("同域不同源不许因速度误删", 2, out.retained.size());
    assertEquals(0, out.removedSlow);
    assertEquals(1, out.closeKept);
  }

  @Test public void reviewSkippedSourcesAreKeptNotDropped() {
    // 待复核源（SKIPPED：探测步骤全过但启用步骤覆盖不全）不参与最快竞争，也不被淘汰误删。
    SourceRecord usable = src(0, "可用", "https://a.com", 0);
    SourceRecord review = src(1, "待复核", "https://a.com/slow", 0);
    List<CheckSourceResult> checks = Arrays.asList(
        ok(usable, 100),
        new CheckSourceResult(review, CheckSourceResult.Status.SKIPPED,
            Arrays.asList("目录未覆盖", "待复核"), "uncovered", 200, SourceKind.NOVEL, 3, 0, 0, 0, 200));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(Arrays.asList(usable, review), checks, 3, null);
    assertEquals(2, out.retained.size());
    assertEquals(0, out.removedSlow);
    assertEquals(1, out.skipped);
  }

  private static Map<String, Object> rawWith(String k, Object v) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", "x");
    raw.put("bookSourceUrl", "https://x.example");
    raw.put(k, v);
    return raw;
  }

  private static SourceRecord src(int order, String name, String url, int type) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("bookSourceType", type);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(order, name, url, raw);
  }

  private static CheckSourceResult ok(SourceRecord s, long ms) {
    return new CheckSourceResult(s, CheckSourceResult.Status.SUCCESS, Collections.<String>emptyList(),
        "ok", ms, SourceKind.of(s), 10, 10, 0, 0, ms);
  }

  private static CheckSourceResult fail(SourceRecord s) {
    return new CheckSourceResult(s, CheckSourceResult.Status.FAILED, Collections.singletonList("搜索失效"),
        "fail", 900, SourceKind.of(s), 10, 2, 8, 0, 0);
  }
}