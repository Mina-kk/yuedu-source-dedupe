package com.mina.yuedu.core;

import com.mina.yuedu.model.SourceRecord;
import java.util.*;
import java.util.Locale;

/**
 * 书源名称相似度计算工具（提取自 MIXUULS/yuedu-source-dedupe）。
 * 用于按名称去重：归一化名称后计算 Levenshtein 距离 + 关键词匹配，
 * 判断两个书源是否指向同一站点。
 *
 * 纯逻辑，不依赖 Android，可单元测试。
 */
public final class NameSimilarity {

  /** 名称合并时优先保留的关键词（权重更高）。 */
  private static final Set<String> PREFERRED_KEYWORDS = new HashSet<>(Arrays.asList(
      "官方", "原版", "正版", "官网", "原始", "主站"));

  /** 去重时可忽略的通用后缀/前缀修饰词。 */
  private static final Set<String> IGNORED_MODIFIERS = new HashSet<>(Arrays.asList(
      "小说网", "小说", "书城", "书屋", "书源", "阅读", "看书",
      "官方", "原版", "正版", "官网", "备用", "镜像", "主站", "分站",
      "最新", "新版", "旧版", "旧站", "新站", "永久", "发布",
      "网址", "地址", "入口", "通道", "首页", "主页",
      "中转", "跳转", "直达", "线路", "链接", "网", "站",
      "com", "cn", "net", "org", "cc", "xyz", "top", "fun",
      "app", "web", "site", "page", "online"));

  private NameSimilarity() {}

  /**
   * 判断两个名称是否指向同一书源（相似度 ≥ threshold）。
   * @param threshold 0.0 ~ 1.0，推荐 0.75
   */
  public static boolean isSimilar(String nameA, String nameB, double threshold) {
    if (nameA == null || nameB == null) return false;
    return isSimilarNormalized(nameA, normalize(nameA), nameB, normalize(nameB), threshold);
  }

  /** 归一化名称已由调用方缓存时使用，避免大批量分组重复做正则清理。 */
  private static boolean isSimilarNormalized(String rawA, String a, String rawB, String b, double threshold) {
    if (rawA == null || rawB == null) return false;
    if (a.isEmpty() || b.isEmpty()) return rawA.equals(rawB); // 都为空时视为相等
    if (a.equals(b)) return true;
    // 短名称（≤4 字符）必须完全相等，避免误合并
    if (a.length() <= 4 || b.length() <= 4) return a.equals(b);
    // 编辑距离至少等于长度差；即使公共前缀和包含关系都满额，
    // 长短差距过大也不可能达到阈值，无需计算 Levenshtein。
    double maxPossible = 1.35 * Math.min(a.length(), b.length()) / Math.max(a.length(), b.length());
    if (maxPossible < threshold) return false;
    double sim = similarity(a, b);
    return sim >= threshold;
  }

  /**
   * 计算两个归一化名称的相似度 (0.0 ~ 1.0)。
   * 综合 Levenshtein 距离 + 公共前缀匹配。
   */
  public static double similarity(String a, String b) {
    if (a == null || b == null) return 0.0;
    if (a.equals(b)) return 1.0;
    if (a.isEmpty() || b.isEmpty()) return 0.0;

    int lenA = a.length();
    int lenB = b.length();
    int maxLen = Math.max(lenA, lenB);
    int dist = levenshtein(a, b);
    double levSim = 1.0 - (double) dist / maxLen;

    // 公共前缀加分（名称开头相同通常意味着同站点）
    int prefix = 0;
    int minLen = Math.min(lenA, lenB);
    while (prefix < minLen && a.charAt(prefix) == b.charAt(prefix)) prefix++;
    double prefixBonus = (double) prefix / maxLen * 0.2; // 最多加 0.2

    // 包含关系加分（A 包含 B 或 B 包含 A）
    double containsBonus = 0.0;
    if (a.contains(b) || b.contains(a)) {
      containsBonus = 0.15 * (double) Math.min(lenA, lenB) / maxLen;
    }

    return Math.min(1.0, levSim + prefixBonus + containsBonus);
  }

  /**
   * 归一化名称：去修饰词、去空格、转小写（英文部分）。
   * 例如 "笔趣阁小说网(官方)" → "笔趣阁"
   */
  public static String normalize(String name) {
    if (name == null) return "";
    String s = name.trim();
    // 清理装饰符号（复用 NameCleaner 逻辑）
    s = NameCleaner.clean(s);
    if (s.isEmpty()) return "";

    // 移除括号及括号内内容（如 "(官方)"、"（备用）"）
    s = s.replaceAll("[（(][^）)]*[）)]", "").trim();
    // 移除常见分隔符（\s 不含全角空格/不间断空格，需显式加入）
    s = s.replaceAll("[_\\-–—・·\\s\\u3000\\u00A0\\u2007\\u202F]+", " ").trim();

    // 从尾部开始，贪婪匹配并移除 IGNORED_MODIFIERS 中的修饰词
    // 先按长度降序排序，优先匹配更长的修饰词
    String result = stripModifiers(s);
    // 如果全部被去掉了，保留原始名称（去除括号后的版本）
    return result.isEmpty() ? s : result;
  }

  /** 从字符串末尾反复移除 IGNORED_MODIFIERS 中的词（无空格也可匹配）。 */
  private static String stripModifiers(String s) {
    if (s == null || s.isEmpty()) return s;
    // 将修饰词按长度降序排列，优先匹配长词
    List<String> sorted = new ArrayList<>(IGNORED_MODIFIERS);
    sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));

    String current = s.trim();
    boolean changed;
    do {
      changed = false;
      for (String mod : sorted) {
        if (mod.isEmpty()) continue;
        // 从末尾匹配（忽略大小写，英文部分）
        if (current.toLowerCase(Locale.ROOT).endsWith(mod.toLowerCase(Locale.ROOT))) {
          String stripped = current.substring(0, current.length() - mod.length()).trim();
          if (!stripped.isEmpty()) {
            current = stripped;
            changed = true;
            break; // 重新开始循环
          }
        }
      }
    } while (changed);
    return current;
  }

  /**
   * 判断名称是否包含"优先保留"关键词。
   */
  public static boolean hasPreferredKeyword(String name) {
    if (name == null) return false;
    for (String kw : PREFERRED_KEYWORDS) {
      if (name.contains(kw)) return true;
    }
    return false;
  }

  /**
   * 计算 Levenshtein 编辑距离。
   */
  public static int levenshtein(String a, String b) {
    if (a == null) a = "";
    if (b == null) b = "";
    int m = a.length();
    int n = b.length();
    // 使用两个一维数组优化空间
    int[] prev = new int[n + 1];
    int[] curr = new int[n + 1];
    for (int j = 0; j <= n; j++) prev[j] = j;
    for (int i = 1; i <= m; i++) {
      curr[0] = i;
      for (int j = 1; j <= n; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        curr[j] = Math.min(
            Math.min(curr[j - 1] + 1, prev[j] + 1),
            prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[n];
  }

  /**
   * 从一组书源中按名称分组相似的书源。
   * 返回每个分组的列表（第一项为保留的书源，其余为重复）。
   * 使用阈值 0.75。
   */
  public static List<List<SourceRecord>> groupByName(List<SourceRecord> sources) {
    return groupByName(sources, 0.75);
  }

  /**
   * 从一组书源中按名称分组相似的书源。
   * @param threshold 相似度阈值，0.0~1.0
   */
  public static List<List<SourceRecord>> groupByName(List<SourceRecord> sources, double threshold) {
    List<List<SourceRecord>> groups = new ArrayList<>();
    if (sources == null || sources.isEmpty()) return groups;

    // normalize 含正则处理，预计算后可避免 N² 次重复清理。
    List<String> normalized = new ArrayList<>(sources.size());
    for (SourceRecord source : sources) normalized.add(normalize(source.getName()));
    boolean[] used = new boolean[sources.size()];
    for (int i = 0; i < sources.size(); i++) {
      if (used[i]) continue;
      List<SourceRecord> group = new ArrayList<>();
      group.add(sources.get(i));
      used[i] = true;
      String nameI = sources.get(i).getName();
      String normalizedI = normalized.get(i);
      for (int j = i + 1; j < sources.size(); j++) {
        if (used[j]) continue;
        String nameJ = sources.get(j).getName();
        if (isSimilarNormalized(nameI, normalizedI, nameJ, normalized.get(j), threshold)) {
          group.add(sources.get(j));
          used[j] = true;
        }
      }
      if (group.size() > 1) {
        groups.add(group);
      }
    }
    // 每组内按质量评分排序，第一个为保留
    for (List<SourceRecord> group : groups) {
      group.sort((a, b) -> {
        int d = Integer.compare(score(b), score(a));
        return d != 0 ? d : Integer.compare(a.getOrder(), b.getOrder());
      });
    }
    return groups;
  }

  /**
   * 评分：与 DedupeEngine.score 一致，用于名称去重分组内排序。
   */
  private static int score(SourceRecord s) {
    int n = 0;
    if (s.getName() != null && !s.getName().trim().isEmpty()) n += 10;
    if (s.getUrl() != null && !s.getUrl().trim().isEmpty()) n += 10;
    // 优先保留含"官方"等关键词的源
    if (hasPreferredKeyword(s.getName())) n += 15;
    for (Object v : s.getRaw().values()) if (v != null && !String.valueOf(v).trim().isEmpty()) n++;
    Object en = s.getRaw().get("enabled");
    if (!(en instanceof Boolean) || (Boolean) en) n += 5;
    Object t = s.getRaw().get("lastUpdateTime");
    if (t instanceof Number) n += DedupeEngine.recencyBonus(((Number) t).longValue());
    else if (t != null) {
      try { n += DedupeEngine.recencyBonus(Long.parseLong(String.valueOf(t))); } catch (Exception ignored) {}
    }
    for (String rk : new String[]{"ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleExplore"}) {
      Object rv = s.getRaw().get(rk);
      if (rv instanceof Map && !((Map<?, ?>) rv).isEmpty()) n += 8;
      else if (rv != null && !String.valueOf(rv).trim().isEmpty()) n += 4;
    }
    return n;
  }
}
