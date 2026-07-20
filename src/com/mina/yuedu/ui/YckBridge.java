package com.mina.yuedu.ui;
import android.webkit.JavascriptInterface;
public final class YckBridge {
 public interface Collector { String collect(String url); }
 private final Collector collector;
 public YckBridge(Collector collector){this.collector=collector;}
 @JavascriptInterface public String addToDedupe(String url){return collector.collect(url);}
}
