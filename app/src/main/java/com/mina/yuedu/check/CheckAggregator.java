package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Combines N independent probe rounds into one CheckSource result. */
public final class CheckAggregator {
  private CheckAggregator() {}

  public static CheckSourceResult combine(SourceRecord source, SourceKind kind, int plannedRounds,
      List<CheckSourceResult> rounds) {
    int n = plannedRounds < 1 ? 1 : plannedRounds;
    int success = 0, fail = 0, timeout = 0;
    List<Long> successTimes = new ArrayList<Long>();
    List<String> groups = new ArrayList<String>();
    long elapsed = 0L;
    if (rounds != null) {
      for (CheckSourceResult round : rounds) {
        if (round == null) continue;
        elapsed += Math.max(0L, round.respondTimeMs);
        if (round.status == CheckSourceResult.Status.SUCCESS) {
          success++;
          successTimes.add(round.medianRespondTimeMs > 0 ? round.medianRespondTimeMs : round.respondTimeMs);
        } else if (round.status == CheckSourceResult.Status.TIMEOUT) {
          timeout++;
          mergeGroups(groups, round.groups);
        } else if (round.status != CheckSourceResult.Status.SKIPPED) {
          fail++;
          mergeGroups(groups, round.groups);
        }
      }
    }
    long median = median(successTimes);
    boolean usable = success * 2 > n;
    CheckSourceResult.Status status;
    String message;
    if (usable) {
      status = CheckSourceResult.Status.SUCCESS;
      message = "校验成功";
    } else if (timeout > fail && timeout >= success) {
      status = CheckSourceResult.Status.TIMEOUT;
      if (!groups.contains("校验超时")) groups.add("校验超时");
      message = groups.isEmpty() ? "timeout" : String.join(",", groups);
    } else {
      status = CheckSourceResult.Status.FAILED;
      message = groups.isEmpty() ? "校验失败" : String.join(",", groups);
    }
    long reported = median > 0 ? median : elapsed;
    return new CheckSourceResult(source, status, groups, message, reported, kind, n, success, fail, timeout, median);
  }

  /** Usable requires success > planned/2. Stop when already usable or remaining rounds cannot catch up. */
  public static boolean decided(int plannedRounds, int success, int done) {
    int n = plannedRounds < 1 ? 1 : plannedRounds;
    int finished = Math.max(0, done);
    int ok = Math.max(0, success);
    int need = n / 2 + 1;
    if (ok >= need) return true;
    int remain = Math.max(0, n - finished);
    return ok + remain < need;
  }

  public static long median(List<Long> times) {
    if (times == null || times.isEmpty()) return 0L;
    List<Long> copy = new ArrayList<Long>(times);
    Collections.sort(copy);
    int n = copy.size();
    if ((n & 1) == 1) return copy.get(n / 2);
    return (copy.get(n / 2 - 1) + copy.get(n / 2)) / 2L;
  }

  private static void mergeGroups(List<String> into, List<String> extra) {
    if (extra == null) return;
    for (String g : extra) {
      if (g != null && !g.isEmpty() && !into.contains(g)) into.add(g);
    }
  }
}
