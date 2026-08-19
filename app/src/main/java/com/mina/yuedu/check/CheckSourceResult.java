package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs) {
    this(source, status, groups, message, respondTimeMs, SourceKind.of(source), 1,
        status == Status.SUCCESS ? 1 : 0,
        status == Status.FAILED ? 1 : 0,
        status == Status.TIMEOUT ? 1 : 0,
        respondTimeMs);
  }

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message,
      long respondTimeMs, SourceKind kind) {
    this(source, status, groups, message, respondTimeMs, kind, 1,
        status == Status.SUCCESS ? 1 : 0,
        status == Status.FAILED ? 1 : 0,
        status == Status.TIMEOUT ? 1 : 0,
        respondTimeMs);
  }

  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message,
      long respondTimeMs, SourceKind kind, int probeRounds, int successCount, int failCount,
      int timeoutCount, long medianRespondTimeMs) {
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
  }

  public boolean isUsable() { return status == Status.SUCCESS; }
}