package com.mina.yuedu.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Split the network-address box into individual HTTP(S) URLs.
 * Users often paste two or three sources on one line, or mix commas /
 * Chinese punctuation / spaces. Line-only splitting then looks like
 * "sometimes 1, sometimes 3".
 */
public final class UrlInputParser {
  private static final Pattern URL = Pattern.compile(
      "https?://[^\\s\\u00A0\\u3000,;，；|\\[\\]<>\"'）)】]+",
      Pattern.CASE_INSENSITIVE);

  private UrlInputParser() {}

  public static List<String> parse(CharSequence raw) {
    LinkedHashSet<String> out = new LinkedHashSet<String>();
    if (raw == null) return new ArrayList<String>();
    String text = raw.toString();
    Matcher m = URL.matcher(text);
    while (m.find()) {
      String url = trimJunk(m.group());
      if (!url.isEmpty()) out.add(url);
    }
    if (!out.isEmpty()) return new ArrayList<String>(out);
    for (String line : text.split("[\\r\\n]+")) {
      String t = line.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return new ArrayList<String>(out);
  }

  static String trimJunk(String url) {
    if (url == null) return "";
    String s = url.trim();
    while (!s.isEmpty()) {
      char c = s.charAt(s.length() - 1);
      if (c == '.' || c == ',' || c == ';' || c == ':' || c == ')' || c == ']'
          || c == '}' || c == '。' || c == '，' || c == '；' || c == '、') {
        s = s.substring(0, s.length() - 1);
      } else {
        break;
      }
    }
    return s;
  }
}
