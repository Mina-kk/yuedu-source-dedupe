package com.mina.yuedu.check;
import com.mina.yuedu.model.SourceRecord;
import java.util.*;
public final class CheckSourceResult {
  public enum Status { SUCCESS, TIMEOUT, FAILED, SKIPPED }
  public final SourceRecord source;
  public final Status status;
  public final List<String> groups;
  public final String message;
  public final long respondTimeMs;
  public final SourceKind kind;
  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs) {
    this(source, status, groups, message, respondTimeMs, SourceKind.of(source));
  }
  public CheckSourceResult(SourceRecord source, Status status, List<String> groups, String message, long respondTimeMs, SourceKind kind) {
    this.source = source; this.status = status;
    this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
    this.message = message; this.respondTimeMs = respondTimeMs;
    this.kind = kind == null ? SourceKind.NOVEL : kind;
  }
  public boolean isUsable() { return status == Status.SUCCESS; }
}
