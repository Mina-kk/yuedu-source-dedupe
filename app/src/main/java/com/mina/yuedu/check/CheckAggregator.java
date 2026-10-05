package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Combines N independent probe rounds into one CheckSource result. */
public final class CheckAggregator {
  private CheckAggregator() {}

  public static CheckSourceResult combine(SourceRecord source, SourceKind kind, int plannedRounds,
      List<CheckSourceResult> rounds) {
    int n = plannedRounds < 1 ? 1 : plannedRounds;
    int success = 0, fail = 0, timeout = 0, skipped = 0;
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
        } else if (round.status == CheckSourceResult.Status.SKIPPED) {
          // 待复核轮（探测步骤全过但部分启用步骤未覆盖）：分组透出但不计失败
          skipped++;
          mergeGroups(groups, round.groups);
        } else {
          fail++;
          mergeGroups(groups, round.groups);
        }
      }
    }
    long median = median(successTimes);
    boolean usable = success * 2 > n;
    CheckSourceResult.Status status;
    String message;
    if (!usable && success == 0 && fail == 0 && timeout == 0 && skipped > 0) {
      // 每一轮都"步骤全过但覆盖不全"：证据不足判死，保留待复核——既不是可用也不是失败，
      // 避免被"仅导出探测通过"误删或被当失效误报。
      status = CheckSourceResult.Status.SKIPPED;
      message = groups.isEmpty() ? "覆盖不全，待复核" : String.join(",", groups);
      Set<String> skippedSteps = new LinkedHashSet<String>();
      for (CheckSourceResult round : rounds) {
        if (round != null && round.succeededSteps != null) skippedSteps.addAll(round.succeededSteps);
      }
      return new CheckSourceResult(source, status, groups, message, elapsed, kind, n, 0, 0, 0,
          median, skippedSteps);
    }
    if (usable) {
      status = CheckSourceResult.Status.SUCCESS;
      message = "网页探测通过，规则未实测";
    } else if (timeout > fail && timeout >= success) {
      status = CheckSourceResult.Status.TIMEOUT;
      if (!groups.contains("校验超时")) groups.add("校验超时");
      message = groups.isEmpty() ? "timeout" : String.join(",", groups);
    } else {
      status = CheckSourceResult.Status.FAILED;
      message = groups.isEmpty() ? "校验失败" : String.join(",", groups);
    }
    long reported = median > 0 ? median : elapsed;
    // 单源 succeededSteps 只取"整轮 SUCCESS"的轮次步骤并集：
    // FAILED 轮里某个步骤没被标记失败不代表它真的通过（可能压根没跑到），
    // 不能把"未失败"反推成"已成功"来拼凑全链路。
    Set<String> succeededSteps = new LinkedHashSet<String>();
    if (rounds != null) {
      for (CheckSourceResult round : rounds) {
        if (round != null && round.status == CheckSourceResult.Status.SUCCESS
            && round.succeededSteps != null) {
          succeededSteps.addAll(round.succeededSteps);
        }
      }
    }
    return new CheckSourceResult(source, status, groups, message, reported, kind, n, success, fail,
        timeout, median, succeededSteps);
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
