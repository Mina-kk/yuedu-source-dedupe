package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckResultIndex;
import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.DuplicateGroup;
import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校验后的同域排序/可选淘汰，零误删收紧版。
 *
 * 校验证据统一走 CheckResultIndex：只认 SourceRecord 实例身份，或 URL+名称+完整
 * raw Map 深等的唯一指纹。绝不用裸 URL 匹配——同 URL 规则不同的源不互相套用证据。
 *
 * 速度排序只是参考/呈现：bucket 内按中位耗时升序，最快的排前；速度本身永远不构成
 * 删除依据。要真正"淘汰"必须同时满足：
 *   1. speedDedupe 显式开启（仅标准/严格/激进等手动模式；「快速准确」网页探测未执行
 *      阅读规则，恒为 false，完全不淘汰）；
 *   2. 候选与最快源是"完全身份等价"（URL+名称+raw 深等，规则/字段完全一致）——即前序
 *      去重本就认定的同一源等价副本；
 *   3. 耗时差超出 CLOSE_ABS_MS / CLOSE_RATIO 之外且确认探测稳定更快。
 * 规则/路径/字段不同的同域源一律保留（计入 closeKept 参考计数，但不建 DuplicateGroup——
 * DuplicateGroup.removed 只放真正被移除的源，避免 DedupeResult.getDuplicateCount()
 * 把"保留"错计成"重复删除"）。
 */
public final class FastestUsableDedupe {
  public static final long CLOSE_ABS_MS = 200L;
  public static final double CLOSE_RATIO = 0.15d;
  public static final int MAX_CONFIRM_ROUNDS = 3;

  public interface ConfirmProbe {
    CheckSourceResult probe(SourceRecord source);
  }

  public static final class Outcome {
    public final List<SourceRecord> retained;
    /** 仅含"真正被移除"的重复组；保留的 close 源不在其中（不会推高 duplicate 计数）。 */
    public final List<DuplicateGroup> groups;
    public final int removedSlow;
    /** 同域但未被移除而保留的源数（仅计数参考，不产生 DuplicateGroup）。 */
    public final int closeKept;
    public final int skipped;
    public Outcome(List<SourceRecord> retained, List<DuplicateGroup> groups, int removedSlow, int closeKept, int skipped) {
      this.retained = Collections.unmodifiableList(new ArrayList<SourceRecord>(retained));
      this.groups = Collections.unmodifiableList(new ArrayList<DuplicateGroup>(groups));
      this.removedSlow = removedSlow;
      this.closeKept = closeKept;
      this.skipped = skipped;
    }
  }

  private FastestUsableDedupe() {}

  /** 手动模式沿用历史行为：速度+可选确认探测淘汰慢源（speedDedupe=true）。 */
  public static Outcome apply(List<SourceRecord> sources, List<CheckSourceResult> checks, int confirmRounds,
      ConfirmProbe probe) {
    return apply(sources, checks, confirmRounds, probe, true);
  }

  /**
   * @param speedDedupe true=手动模式可淘汰（仍仅同域同桶，且收紧身份证据）；
   *                    false=「快速准确」纯排序不淘汰——零误删。
   */
  public static Outcome apply(List<SourceRecord> sources, List<CheckSourceResult> checks, int confirmRounds,
      ConfirmProbe probe, boolean speedDedupe) {
    int rounds = confirmRounds < 1 ? 1 : Math.min(confirmRounds, MAX_CONFIRM_ROUNDS);
    CheckResultIndex index = CheckResultIndex.of(checks);
    Map<String, List<CheckSourceResult>> buckets = new LinkedHashMap<String, List<CheckSourceResult>>();
    List<SourceRecord> skipped = new ArrayList<SourceRecord>();
    List<SourceRecord> unusable = new ArrayList<SourceRecord>();
    if (sources != null) {
      for (SourceRecord source : sources) {
        CheckSourceResult r = index.find(source);
        if (r == null || r.status == CheckSourceResult.Status.SKIPPED) {
          skipped.add(source);
          continue;
        }
        if (!r.isUsable()) {
          unusable.add(source);
          continue;
        }
        String key = bucketKey(source, r.kind);
        List<CheckSourceResult> g = buckets.get(key);
        if (g == null) {
          g = new ArrayList<CheckSourceResult>();
          buckets.put(key, g);
        }
        g.add(r);
      }
    }

    List<SourceRecord> kept = new ArrayList<SourceRecord>();
    List<DuplicateGroup> groups = new ArrayList<DuplicateGroup>();
    int removedSlow = 0;
    int closeKept = 0;
    for (Map.Entry<String, List<CheckSourceResult>> e : buckets.entrySet()) {
      List<CheckSourceResult> g = e.getValue();
      g.sort(RANK);
      CheckSourceResult best = g.get(0);
      kept.add(best.source);
      if (g.size() == 1) continue;
      List<SourceRecord> removed = new ArrayList<SourceRecord>();
      for (int i = 1; i < g.size(); i++) {
        CheckSourceResult cand = g.get(i);
        // 淘汰三重门：speedDedupe 开 + 完全身份等价 + 明显更快且确认稳定。
        // 任一不满足即保留（计 closeKept 参考），绝不因"同域但不同源"误删。
        boolean removable = speedDedupe
            && fullIdentityEquals(best.source, cand.source)
            && !isClose(best.medianRespondTimeMs, cand.medianRespondTimeMs)
            && stablyFaster(best, cand, rounds, probe);
        if (removable) {
          removed.add(cand.source);
          removedSlow++;
        } else {
          kept.add(cand.source);
          closeKept++;
        }
      }
      // 只为真正移除的源建组：保留的 close 源不进 DuplicateGroup.removed，
      // 防止 getDuplicateCount() 把"保留"算成"重复删除"。
      if (!removed.isEmpty()) {
        groups.add(new DuplicateGroup(e.getKey(), reason(best, rounds), best.source, removed));
      }
    }
    kept.addAll(unusable);
    kept.addAll(skipped);
    kept.sort(Comparator.comparingInt(SourceRecord::getOrder));
    return new Outcome(kept, groups, removedSlow, closeKept, skipped.size());
  }

  static boolean isClose(long a, long b) {
    long d = Math.abs(a - b);
    long m = Math.max(a, b);
    if (d <= CLOSE_ABS_MS) return true;
    return m > 0 && d <= (long) Math.ceil(m * CLOSE_RATIO);
  }

  /** 完全身份等价：URL+名称+完整 raw Map 递归深等（与 CheckResultIndex 指纹口径一致）。 */
  static boolean fullIdentityEquals(SourceRecord a, SourceRecord b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    return eq(a.getUrl(), b.getUrl()) && eq(a.getName(), b.getName()) && a.getRaw().equals(b.getRaw());
  }

  static boolean stablyFaster(CheckSourceResult faster, CheckSourceResult slower, int rounds, ConfirmProbe probe) {
    if (faster == null || slower == null) return false;
    if (probe == null) {
      return faster.medianRespondTimeMs < slower.medianRespondTimeMs
          && !isClose(faster.medianRespondTimeMs, slower.medianRespondTimeMs);
    }
    int n = rounds < 1 ? 1 : Math.min(rounds, MAX_CONFIRM_ROUNDS);
    int needed = (n + 1) / 2;
    int wins = 0;
    int losses = 0;
    for (int i = 0; i < n; i++) {
      CheckSourceResult a = probe.probe(faster.source);
      CheckSourceResult b = probe.probe(slower.source);
      if (a == null || b == null) return false;
      int cmp = comparePair(a, b);
      if (cmp < 0) wins++;
      else if (cmp > 0) losses++;
      if (wins >= needed) return true;
      if (wins + (n - i - 1) < needed) return false;
      if (losses >= needed) return false;
    }
    return wins >= needed;
  }

  private static int comparePair(CheckSourceResult a, CheckSourceResult b) {
    if (a.isUsable() && !b.isUsable()) return -1;
    if (!a.isUsable() && b.isUsable()) return 1;
    if (!a.isUsable() || !b.isUsable()) return 0;
    long ta = a.medianRespondTimeMs > 0 ? a.medianRespondTimeMs : a.respondTimeMs;
    long tb = b.medianRespondTimeMs > 0 ? b.medianRespondTimeMs : b.respondTimeMs;
    if (isClose(ta, tb)) return 0;
    return Long.compare(ta, tb);
  }

  public static String bucketKey(SourceRecord source, SourceKind kind) {
    SourceKind k = kind == null ? SourceKind.of(source) : kind;
    String host;
    try {
      host = UrlNormalizer.hostKey(source.getUrl());
    } catch (Exception e) {
      host = "invalid:" + (source == null ? 0 : source.getOrder());
    }
    return k.name() + "|" + host;
  }

  private static String reason(CheckSourceResult best, int rounds) {
    double seconds = best.medianRespondTimeMs / 1000.0d;
    return String.format(Locale.CHINA, "同类型同域名仅保留最快可用（中位 %.2fs，确认 %d 轮）", seconds, rounds);
  }

  private static int completeness(SourceRecord s) {
    int n = 0;
    if (s.getName() != null && !s.getName().trim().isEmpty()) n += 10;
    for (String rk : new String[]{"ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleExplore"}) {
      Object rv = s.getRaw().get(rk);
      if (rv instanceof Map && !((Map<?, ?>) rv).isEmpty()) n += 8;
      else if (rv != null && !String.valueOf(rv).trim().isEmpty()) n += 4;
    }
    return n;
  }

  private static final Comparator<CheckSourceResult> RANK = (a, b) -> {
    int u = Boolean.compare(b.isUsable(), a.isUsable());
    if (u != 0) return u;
    int t = Long.compare(a.medianRespondTimeMs, b.medianRespondTimeMs);
    if (t != 0) return t;
    int c = Integer.compare(completeness(b.source), completeness(a.source));
    if (c != 0) return c;
    return Integer.compare(a.source.getOrder(), b.source.getOrder());
  };

  private static boolean eq(Object a, Object b) { return a == null ? b == null : a.equals(b); }
}
