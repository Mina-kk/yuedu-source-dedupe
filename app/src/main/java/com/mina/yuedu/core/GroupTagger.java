package com.mina.yuedu.core;

import com.mina.yuedu.check.SourceKind;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Appends ✔年月日+分类+数量 to bookSourceGroup without replacing original tokens. */
public final class GroupTagger {
  private static final Pattern LEGACY_QUALITY =
      Pattern.compile("^✔\\d{4}-\\d{1,2}-\\d{1,2}检验去重（优质\\d+）$");
  private static final Pattern TYPE_STAMP =
      Pattern.compile("^✔\\d{4}-\\d{1,2}-\\d{1,2}(?:小说|漫画|视频|音频|文件)（\\d+）$");

  private GroupTagger() {}

  public static String today() {
    return new SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(new Date());
  }

  public static String typeStamp(SourceKind kind, int count, String date) {
    SourceKind k = kind == null ? SourceKind.NOVEL : kind;
    String d = (date == null || date.trim().isEmpty()) ? today() : date.trim();
    int n = Math.max(0, count);
    return "✔" + d + k.label + "（" + n + "）";
  }

  public static boolean isStamp(String token) {
    if (token == null) return false;
    String t = token.trim();
    return LEGACY_QUALITY.matcher(t).matches() || TYPE_STAMP.matcher(t).matches();
  }

  public static String apply(String original, SourceKind kind, int count, String date) {
    List<String> tokens = tokens(original);
    tokens.removeIf(GroupTagger::isStamp);
    String stamp = typeStamp(kind, count, date);
    if (!tokens.contains(stamp)) tokens.add(stamp);
    return join(tokens);
  }

  static List<String> tokens(String original) {
    List<String> out = new ArrayList<String>();
    if (original == null) return out;
    String[] parts = original.split(",");
    for (String part : parts) {
      String t = part.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  private static String join(List<String> tokens) {
    StringBuilder sb = new StringBuilder();
    for (String t : tokens) {
      if (sb.length() > 0) sb.append(',');
      sb.append(t);
    }
    return sb.toString();
  }
}
