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
  @Test public void keepsFastestStableUsableOnSameKindAndHost() {
    SourceRecord fast = src(0, "快", "https://a.com", 0);
    SourceRecord slow = src(1, "慢", "https://www.a.com/path#🎃", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 120), ok(slow, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, (source) -> source == fast ? ok(fast, 110) : ok(slow, 900));
    assertEquals(1, out.retained.size());
    assertEquals("快", out.retained.get(0).getName());
    assertEquals(1, out.removedSlow);
    assertTrue(out.groups.get(0).getReason().contains("同类型同域名仅保留最快可用"));
  }

  @Test public void closeTimesAreBothKept() {
    SourceRecord a = src(0, "A", "https://a.com", 0);
    SourceRecord b = src(1, "B", "https://a.com/other", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(a, 200), ok(b, 210));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(Arrays.asList(a, b), checks, 10, null);
    assertEquals(2, out.retained.size());
    assertEquals(1, out.closeKept);
    boolean saw = false;
    for (com.mina.yuedu.model.DuplicateGroup g : out.groups) {
      if ("耗时接近，未淘汰".equals(g.getReason())) saw = true;
    }
    assertTrue(saw);
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
    SourceRecord fast = src(0, "快", "https://a.com", 0);
    SourceRecord slow = src(1, "慢", "https://www.a.com/path", 0);
    List<CheckSourceResult> checks = Arrays.asList(ok(fast, 120), ok(slow, 800));
    FastestUsableDedupe.Outcome out = FastestUsableDedupe.apply(
        Arrays.asList(fast, slow), checks, 10, null);
    assertEquals(1, out.retained.size());
    assertEquals("快", out.retained.get(0).getName());
    assertEquals(1, out.removedSlow);
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