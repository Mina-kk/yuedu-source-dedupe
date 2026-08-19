package com.mina.yuedu.core;

public final class ElapsedFormat {
  private ElapsedFormat() {}

  public static String format(long ms) {
    long v = Math.max(0L, ms);
    long totalSec = v / 1000L;
    long hour = totalSec / 3600L;
    long min = (totalSec % 3600L) / 60L;
    long sec = totalSec % 60L;
    if (hour > 0) return hour + " 小时 " + min + " 分 " + sec + " 秒";
    if (min > 0) return min + " 分 " + sec + " 秒";
    if (totalSec > 0) return totalSec + " 秒";
    return v + " 毫秒";
  }
}
