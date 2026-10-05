package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CheckAggregatorTest {
  @Test public void sixSuccessOutOfTenIsUsableWithMedianOfSuccessRounds() {
    SourceRecord source = src("快源", "https://a.example");
    List<CheckSourceResult> rounds = new ArrayList<CheckSourceResult>();
    long[] times = {100, 300, 200, 400, 500, 250};
    for (long t : times) rounds.add(ok(source, t));
    for (int i = 0; i < 4; i++) rounds.add(fail(source, 900));
    CheckSourceResult r = CheckAggregator.combine(source, SourceKind.NOVEL, 10, rounds);
    assertTrue(r.isUsable());
    assertEquals(6, r.successCount);
    assertEquals(4, r.failCount);
    assertEquals(10, r.probeRounds);
    List<Long> success = Arrays.asList(100L, 200L, 250L, 300L, 400L, 500L);
    assertEquals(CheckAggregator.median(success), r.medianRespondTimeMs);
  }

  @Test public void sixFailuresOutOfTenAreUnusable() {
    SourceRecord source = src("慢源", "https://b.example");
    List<CheckSourceResult> rounds = new ArrayList<CheckSourceResult>();
    for (int i = 0; i < 4; i++) rounds.add(ok(source, 80));
    for (int i = 0; i < 6; i++) rounds.add(fail(source, 900));
    CheckSourceResult r = CheckAggregator.combine(source, SourceKind.NOVEL, 10, rounds);
    assertFalse(r.isUsable());
    assertEquals(CheckSourceResult.Status.FAILED, r.status);
    assertEquals(4, r.successCount);
    assertEquals(6, r.failCount);
  }

  @Test public void evenMedianAveragesTheMiddlePair() {
    assertEquals(250L, CheckAggregator.median(Arrays.asList(100L, 200L, 300L, 400L)));
    assertEquals(0L, CheckAggregator.median(Collections.<Long>emptyList()));
  }

  @Test public void decidedStopsOnceMajorityIsSettled() {
    assertFalse(CheckAggregator.decided(10, 5, 5));
    assertTrue(CheckAggregator.decided(10, 6, 6));
    assertTrue(CheckAggregator.decided(10, 0, 5));
    assertFalse(CheckAggregator.decided(10, 0, 4));
  }

  // ===== succeededSteps 跨轮拼凑回归 =====

  @Test public void succeededStepsOnlyUnionOfSuccessRounds() {
    // 第1轮搜索成、第2轮发现成但整轮 FAILED → 合并后 succeededSteps 只能来自 SUCCESS 轮，
    // 不能跨轮拼凑出"搜索+发现都成功"。
    SourceRecord s = src("源", "https://a.example");
    CheckSourceResult r1 = round(s, CheckSourceResult.Status.FAILED, set("搜索"), 100);
    CheckSourceResult r2 = round(s, CheckSourceResult.Status.FAILED, set("发现"), 100);
    // 全 FAILED → 整体 FAILED，succeededSteps 应为空
    CheckSourceResult c = CheckAggregator.combine(s, SourceKind.NOVEL, 2, Arrays.asList(r1, r2));
    assertEquals(CheckSourceResult.Status.FAILED, c.status);
    assertTrue(c.succeededSteps.isEmpty());
  }

  @Test public void succeededStepsUnionAcrossSuccessRounds() {
    // 两轮都 SUCCESS 但命中步骤不同 → 并集正确（真实成功轮才计）。
    SourceRecord s = src("源", "https://a.example");
    CheckSourceResult r1 = round(s, CheckSourceResult.Status.SUCCESS, set("搜索", "详情"), 100);
    CheckSourceResult r2 = round(s, CheckSourceResult.Status.SUCCESS, set("搜索", "详情", "目录"), 120);
    CheckSourceResult c = CheckAggregator.combine(s, SourceKind.NOVEL, 2, Arrays.asList(r1, r2));
    assertEquals(CheckSourceResult.Status.SUCCESS, c.status);
    assertEquals(set("搜索", "详情", "目录"), c.succeededSteps);
  }

  private static java.util.Set<String> set(String... items) {
    return new LinkedHashSet<String>(Arrays.asList(items));
  }

  private static CheckSourceResult round(SourceRecord s, CheckSourceResult.Status st,
      java.util.Set<String> steps, long ms) {
    return new CheckSourceResult(s, st, Collections.<String>emptyList(), "m", ms,
        SourceKind.NOVEL, 1, st == CheckSourceResult.Status.SUCCESS ? 1 : 0,
        st == CheckSourceResult.Status.FAILED ? 1 : 0, st == CheckSourceResult.Status.TIMEOUT ? 1 : 0,
        ms, steps);
  }

  private static SourceRecord src(String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    return new SourceRecord(0, name, url, raw);
  }

  private static CheckSourceResult ok(SourceRecord s, long ms) {
    return new CheckSourceResult(s, CheckSourceResult.Status.SUCCESS, Collections.<String>emptyList(), "ok", ms);
  }

  private static CheckSourceResult fail(SourceRecord s, long ms) {
    return new CheckSourceResult(s, CheckSourceResult.Status.FAILED, Collections.singletonList("搜索失效"), "fail", ms);
  }
}