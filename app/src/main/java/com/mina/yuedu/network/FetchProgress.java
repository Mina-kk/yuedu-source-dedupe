package com.mina.yuedu.network;

public final class FetchProgress {
  private final int total, completed, succeeded, failed, discoveredSources, retrying;
  private final boolean running, cancelled;

  public FetchProgress(int total, int completed, int succeeded, int failed, int discoveredSources,
      boolean running, boolean cancelled) {
    this(total, completed, succeeded, failed, discoveredSources, running, cancelled, 0);
  }

  public FetchProgress(int total, int completed, int succeeded, int failed, int discoveredSources,
      boolean running, boolean cancelled, int retrying) {
    this.total = total;
    this.completed = completed;
    this.succeeded = succeeded;
    this.failed = failed;
    this.discoveredSources = discoveredSources;
    this.running = running;
    this.cancelled = cancelled;
    this.retrying = Math.max(0, retrying);
  }

  public int getTotal() { return total; }
  public int getCompleted() { return completed; }
  public int getSucceeded() { return succeeded; }
  public int getFailed() { return failed; }
  public int getDiscoveredSources() { return discoveredSources; }
  public int getRetrying() { return retrying; }
  public boolean isRunning() { return running; }
  public boolean isCancelled() { return cancelled; }
}