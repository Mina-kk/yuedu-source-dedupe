package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CheckSourceResult {
  public enum Status { SUCCESS, TIMEOUT, FAILED, SKIPPED }

  public final SourceRecord source;
  public final Status status;
  public final List<String> groups;
  public final String message;
  public final long respondTimeMs;
  public final SourceKind kind;
  public final int probeRounds;
  public final int successCount;
  public final int failCount;
  public final int timeoutCount;
  public final long medianRespondTimeMs;
  /** 该源校验通过的步骤（"搜索"、"发现"、"详情"、"目录"、"正文"），用于分阶段结果与合并同域校验。 */
  public final Set<String> succeededSteps;

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs) {
    this(source, status, groups, message, respondTimeMs, SourceKind.of(source), 1,
        status == Status.SUCCESS ? 1 : 0,
        status == Status.FAILED ? 1 : 0,
        status == Status.TIMEOUT ? 1 : 0,
        respondTimeMs,
        Collections.<String>emptySet());
  }

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message,
      long respondTimeMs, SourceKind kind) {
    this(source, status, groups, message, respondTimeMs, kind, 1,
        status == Status.SUCCESS ? 1 : 0,
        status == Status.FAILED ? 1 : 0,
        status == Status.TIMEOUT ? 1 : 0,
        respondTimeMs,
        Collections.<String>emptySet());
  }

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message,
      long respondTimeMs, SourceKind kind, int probeRounds, int successCount, int failCount,
      int timeoutCount, long medianRespondTimeMs) {
    this(source, status, groups, message, respondTimeMs, kind, probeRounds, successCount, failCount,
        timeoutCount, medianRespondTimeMs, Collections.<String>emptySet());
  }

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message,
      long respondTimeMs, SourceKind kind, int probeRounds, int successCount, int failCount,
      int timeoutCount, long medianRespondTimeMs, Set<String> succeededSteps) {
    this.source = source;
    this.status = status;
    this.groups = Collections.unmodifiableList(new ArrayList<String>(groups));
    this.message = message;
    this.respondTimeMs = respondTimeMs;
    this.kind = kind == null ? SourceKind.NOVEL : kind;
    this.probeRounds = Math.max(0, probeRounds);
    this.successCount = Math.max(0, successCount);
    this.failCount = Math.max(0, failCount);
    this.timeoutCount = Math.max(0, timeoutCount);
    this.medianRespondTimeMs = medianRespondTimeMs;
    this.succeededSteps = Collections.unmodifiableSet(new LinkedHashSet<String>(
        succeededSteps == null ? Collections.<String>emptySet() : succeededSteps));
  }

  public boolean isUsable() { return status == Status.SUCCESS; }
}
