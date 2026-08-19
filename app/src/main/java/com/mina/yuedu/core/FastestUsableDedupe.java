package com.mina.yuedu.core;

import com.mina.yuedu.check.CheckSourceResult;
import com.mina.yuedu.check.SourceKind;
import com.mina.yuedu.model.DuplicateGroup;
import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * After CheckSource, keep the fastest stable usable source per (SourceKind, domain).
 * www is treated as the same host. Identity tags and paths are ignored for this bucket.
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
    public final List<DuplicateGroup> groups;
    public final int removedSlow;
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

  public static Outcome apply(List<SourceRecord> sources, List<CheckSourceResult> checks, int confirmRounds,
      ConfirmProbe probe) {
    int rounds = confirmRounds < 1 ? 1 : Math.min(confirmRounds, MAX_CONFIRM_ROUNDS);
    Map<SourceRecord, CheckSourceResult> bySource = index(checks);
    Map<String, List<CheckSourceResult>> buckets = new LinkedHashMap<String, List<CheckSourceResult>>();
    List<SourceRecord> skipped = new ArrayList<SourceRecord>();
    List<SourceRecord> unusable = new ArrayList<SourceRecord>();
    if (sources != null) {
      for (SourceRecord source : sources) {
        CheckSourceResult r = bySource.get(source);
        if (r == null && source.getUrl() != null) r = byUrl(checks, source.getUrl());
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
      List<SourceRecord> close = new ArrayList<SourceRecord>();
      for (int i = 1; i < g.size(); i++) {
        CheckSourceResult cand = g.get(i);
        if (isClose(best.medianRespondTimeMs, cand.medianRespondTimeMs)
            || !stablyFaster(best, cand, rounds, probe)) {
          kept.add(cand.source);
          close.add(cand.source);
          closeKept++;
        } else {
          removed.add(cand.source);
          removedSlow++;
        }
      }
      if (!removed.isEmpty()) {
        groups.add(new DuplicateGroup(e.getKey(), reason(best, rounds), best.source, removed));
      }
      if (!close.isEmpty()) {
        groups.add(new DuplicateGroup(e.getKey() + "|close", "耗时接近，未淘汰", best.source, close));
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

  private static Map<SourceRecord, CheckSourceResult> index(List<CheckSourceResult> checks) {
    Map<SourceRecord, CheckSourceResult> map = new HashMap<SourceRecord, CheckSourceResult>();
    if (checks == null) return map;
    for (CheckSourceResult r : checks) {
      if (r != null && r.source != null) map.put(r.source, r);
    }
    return map;
  }

  private static CheckSourceResult byUrl(List<CheckSourceResult> checks, String url) {
    if (checks == null) return null;
    for (CheckSourceResult r : checks) {
      if (r != null && r.source != null && url.equals(r.source.getUrl())) return r;
    }
    return null;
  }
}
