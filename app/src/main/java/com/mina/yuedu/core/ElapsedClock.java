package com.mina.yuedu.core;

/**
 * Independent wall-clock for a check run. Ticks from start() until stop(),
 * and does not wait for a source callback to advance.
 */
public final class ElapsedClock {
  private long startedAt;
  private long stoppedAt;
  private boolean running;

  public synchronized void start(long nowMs) {
    startedAt = nowMs;
    stoppedAt = 0L;
    running = true;
  }

  public synchronized void stop(long nowMs) {
    if (!running) return;
    stoppedAt = Math.max(nowMs, startedAt);
    running = false;
  }

  public synchronized void reset() {
    startedAt = 0L;
    stoppedAt = 0L;
    running = false;
  }

  public synchronized boolean isRunning() {
    return running;
  }

  public synchronized long elapsed(long nowMs) {
    if (startedAt <= 0L) return 0L;
    if (running) return Math.max(0L, nowMs - startedAt);
    return Math.max(0L, stoppedAt - startedAt);
  }

  public String format(long nowMs) {
    return ElapsedFormat.format(elapsed(nowMs));
  }
}
