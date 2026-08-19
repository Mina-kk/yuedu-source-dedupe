package com.mina.yuedu.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Thread-safe extra URLs that have not been flushed into the text box yet.
 * YCK / JSON-menu adds can land here from a WebView binder thread without
 * waiting on the UI, so rapid "add 2–3 sources" cannot drop entries.
 */
public final class UrlInbox {
  private final LinkedHashSet<String> extra = new LinkedHashSet<String>();

  public synchronized boolean add(String raw) {
    return add(raw, "");
  }

  public synchronized boolean contains(String url) {
    return url != null && extra.contains(url);
  }

  public synchronized List<String> merge(CharSequence boxText) {
    LinkedHashSet<String> all = new LinkedHashSet<String>(UrlInputParser.parse(boxText));
    all.addAll(extra);
    return new ArrayList<String>(all);
  }

  /** Merge box + inbox, then clear pending extras (box becomes source of truth). */
  public synchronized List<String> takeAll(CharSequence boxText) {
    List<String> all = merge(boxText);
    extra.clear();
    return all;
  }

  public synchronized boolean add(String raw, CharSequence boxText) {
    LinkedHashSet<String> known = new LinkedHashSet<String>(UrlInputParser.parse(boxText));
    known.addAll(extra);
    List<String> parsed = UrlInputParser.parse(raw);
    if (parsed.isEmpty() && raw != null) {
      String t = raw.trim();
      if (!t.isEmpty()) parsed = java.util.Collections.singletonList(t);
    }
    boolean any = false;
    for (String u : parsed) {
      if (u != null && known.add(u) && extra.add(u)) any = true;
    }
    return any;
  }

  public synchronized void clear() {
    extra.clear();
  }

  public static String join(List<String> urls) {
    if (urls == null || urls.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < urls.size(); i++) {
      if (i > 0) sb.append('\n');
      sb.append(urls.get(i));
    }
    return sb.toString();
  }
}