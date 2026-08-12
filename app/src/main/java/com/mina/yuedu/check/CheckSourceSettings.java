package com.mina.yuedu.check;
public final class CheckSourceSettings {
  public static final int MAX_CONCURRENCY = 100;
  public long timeoutSeconds = 180;
  public boolean checkSearch = true;
  public boolean checkDiscovery = true;
  public boolean checkInfo = true;
  public boolean checkCategory = true;
  public boolean checkContent = true;
  public String keyword = "我的";
  public int concurrency = 8;

  public void normalize() {
    if (timeoutSeconds < 1) timeoutSeconds = 1;
    if (timeoutSeconds > 300) timeoutSeconds = 300;
    if (!checkSearch && !checkDiscovery) checkSearch = true;
    if (!checkInfo) { checkCategory = false; checkContent = false; }
    if (!checkCategory) checkContent = false;
    if (concurrency < 1) concurrency = 1;
    if (concurrency > MAX_CONCURRENCY) concurrency = MAX_CONCURRENCY;
  }

  public long timeoutMillis() { return timeoutSeconds * 1000L; }
}
