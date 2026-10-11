package com.mina.yuedu.check;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class AnalyzeUrlLite {
  public final String url;
  public final String method;
  public final String body;
  public final Map<String, String> headers;
  public final boolean usesJs;
  /** true = 替换后 url/body 里仍残留 {{...}} 或 {book.xxx} 模板变量（本实现没覆盖到的表达式），
   *  调用方不应把拼出来的地址直接发请求，宜记该步"未覆盖"而非"失效"。 */
  public final boolean unresolved;

  private AnalyzeUrlLite(String url, String method, String body, Map<String, String> headers,
                         boolean usesJs, boolean unresolved) {
    this.url = url;
    this.method = method;
    this.body = body;
    this.headers = headers;
    this.usesJs = usesJs;
    this.unresolved = unresolved;
  }

  /** 兼容旧签名：等价于 vars 为空（book.name 回退 keyword、book.origin/baseUrl 系回退 baseUrl）。 */
  public static AnalyzeUrlLite parse(String raw, String baseUrl, String keyword, int page) {
    return parse(raw, baseUrl, keyword, page, null);
  }

  /**
   * 在 4 参行为基础上追加 book/上下文变量替换（容忍 "{{ book.name }}" 两侧空格）：
   *   book.name、book.author → UTF-8 URL 编码（与 key 相同的编码方式）；
   *   book.bookUrl、book.tocUrl、book.origin、baseUrl、source.bookSourceUrl、source.key → 原样。
   * 值取 vars 同名键；vars 缺失时 book.name 回退 keyword，
   * book.origin/source.bookSourceUrl/source.key/baseUrl 回退 baseUrl；其余缺失不替换。
   */
  public static AnalyzeUrlLite parse(String raw, String baseUrl, String keyword, int page,
                                     Map<String, String> vars) {
    if (raw == null) raw = "";
    String s = raw.trim();
    String lowerAll = s.toLowerCase(Locale.ROOT);
    boolean usesJs = s.contains("<js>") || s.contains("</js>") || s.contains("@js:") || lowerAll.contains("javascript");
    String method = "GET";
    String body = null;
    Map<String, String> headers = new HashMap<>();

    String urlPart = s;
    String opt = null;
    int comma = s.lastIndexOf(",{");
    if (comma > 0) {
      urlPart = s.substring(0, comma).trim();
      opt = s.substring(comma + 1).trim();
    }

    String keyEnc = enc(keyword);
    long pageNum = Math.max(1, page);
    String pageStr = String.valueOf(pageNum);
    urlPart = urlPart
        .replace("{{key}}", keyEnc)
        .replace("{{page}}", pageStr)
        .replace("{key}", keyEnc)
        .replace("{page}", pageStr)
        .replace("{{host}}", hostOf(baseUrl))
        .replace("{host}", hostOf(baseUrl));
    urlPart = replaceBookVars(urlPart, vars, keyword, baseUrl);
    urlPart = resolveTemplateExprs(urlPart, keyword, keyEnc, pageNum);

    if (opt != null && opt.startsWith("{") && opt.endsWith("}")) {
      String lower = opt.toLowerCase(Locale.ROOT);
      if (lower.contains("method") && lower.contains("post")) method = "POST";
      String bodyVal = extractJsonString(opt, "body");
      if (bodyVal != null) {
        body = bodyVal
            .replace("{{key}}", keyEnc)
            .replace("{key}", keyEnc)
            .replace("{{page}}", pageStr)
            .replace("{page}", pageStr);
        body = replaceBookVars(body, vars, keyword, baseUrl);
        body = resolveTemplateExprs(body, keyword, keyEnc, pageNum);
      }
      String ua = extractJsonString(opt, "User-Agent");
      if (ua == null) ua = extractJsonString(opt, "userAgent");
      if (ua != null) headers.put("User-Agent", ua);
      String cookie = extractJsonString(opt, "Cookie");
      if (cookie != null) headers.put("Cookie", cookie);
      String referer = extractJsonString(opt, "Referer");
      if (referer != null) headers.put("Referer", referer);
    }

    String url = absolutize(urlPart, baseUrl);
    boolean unresolved = hasTemplateVar(url) || (body != null && hasTemplateVar(body));
    return new AnalyzeUrlLite(url, method, body, headers, usesJs, unresolved);
  }

  /**
   * 粗判一条规则是否"URL 模板"（区别于 .read@href、//a/@href 这类 CSS/XPath 提取规则）：
   * trim 后以 http:///https:///单斜杠开头，或含 {{ 即视为 URL 模板；
   * // 开头按 XPath 提取规则处理，返回 false。
   */
  public static boolean looksLikeUrlTemplate(String rule) {
    if (rule == null) return false;
    String t = rule.trim();
    if (t.isEmpty()) return false;
    if (t.contains("{{")) return true;
    if (t.startsWith("http://") || t.startsWith("https://")) return true;
    return t.startsWith("/") && !t.startsWith("//");
  }

  /** 替换 {{...}}（允许两侧空白）与 {name} 单花括号形式的 book/上下文变量。 */
  private static String replaceBookVars(String text, Map<String, String> vars,
                                        String keyword, String baseUrl) {
    if (text == null || text.isEmpty() || text.indexOf('{') < 0) return text;
    String out = text;
    // 顺序无关：每个名字只替换自身占位
    out = putVar(out, "book.name", enc(var(vars, "book.name", keyword)));
    out = putVar(out, "book.author", enc(var(vars, "book.author", null)));
    out = putVar(out, "book.bookUrl", var(vars, "book.bookUrl", null));
    out = putVar(out, "book.tocUrl", var(vars, "book.tocUrl", null));
    out = putVar(out, "book.origin", var(vars, "book.origin", baseUrl));
    out = putVar(out, "baseUrl", var(vars, "baseUrl", baseUrl));
    out = putVar(out, "source.bookSourceUrl", var(vars, "source.bookSourceUrl", baseUrl));
    out = putVar(out, "source.key", var(vars, "source.key", baseUrl));
    return out;
  }

  private static String var(Map<String, String> vars, String name, String fallback) {
    if (vars != null) {
      String v = vars.get(name);
      if (v != null) return v;
    }
    return fallback;
  }

  /** 把 {{name}}（两侧可有空白）与 {name} 替换为 value；value 为 null 时不替换（留给 unresolved）。 */
  private static String putVar(String text, String name, String value) {
    if (value == null) return text;
    String dq = Pattern.quote("{{");
    String eq = Pattern.quote("}}");
    // {{ name }} 容忍两侧空白
    text = text.replaceAll(dq + "\\s*" + Pattern.quote(name) + "\\s*" + eq,
        java.util.regex.Matcher.quoteReplacement(value));
    // 单花括号形式 {book.xxx}
    text = text.replace("{" + name + "}", value);
    return text;
  }

  private static String enc(String v) {
    try {
      return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8.name());
    } catch (Exception e) {
      return v == null ? "" : v;
    }
  }

  // ---- {{...}} 表达式安全求值（不用脚本引擎/eval）----
  // 覆盖 Legado 搜索 URL 里最常见的三种形态：
  //   1) {{ key }} / {{ page }} 两侧空白；
  //   2) 编码函数：java.encodeURI(key)/java.encodeURI(key,"gbk")/encodeURIComponent(key)/encodeURI(key)；
  //   3) 纯算术表达式：内容只含数字、page、空白、+ - * / % ( )，用递归下降求整数值。
  // 求值失败（除零、非法表达式、不支持的 charset）一律保持原样 → 交给 unresolved 判定。

  private static final Pattern TEMPLATE_EXPR =
      Pattern.compile("\\{\\{(.*?)\\}\\}", Pattern.DOTALL);
  private static final Pattern ENCODE_FN = Pattern.compile(
      "(?i)^\\s*(?:(java)\\s*\\.\\s*)?(encodeURI|encodeURIComponent)\\s*\\(\\s*key\\s*"
          + "(?:,\\s*(['\"])([^'\"]*)\\3\\s*)?\\)\\s*$");

  /** 扫描文本中的 {{...}}，对可安全求值的表达式就地替换；其余原样保留。 */
  private static String resolveTemplateExprs(String text, String keyword, String keyEnc, long page) {
    if (text == null || !text.contains("{{")) return text;
    java.util.regex.Matcher m = TEMPLATE_EXPR.matcher(text);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String inner = m.group(1).trim();
      String val = evalTemplateExpr(inner, keyword, keyEnc, page);
      m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
          val != null ? val : m.group(0)));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  private static String evalTemplateExpr(String inner, String keyword, String keyEnc, long page) {
    if (inner.isEmpty()) return null;
    if (inner.equalsIgnoreCase("key")) return keyEnc;
    if (inner.equalsIgnoreCase("page")) return String.valueOf(page);
    java.util.regex.Matcher enc = ENCODE_FN.matcher(inner);
    if (enc.matches()) {
      // group1=java 前缀（忽略），group2=encodeURI/encodeURIComponent，group4=可选 charset
      String charset = enc.group(4);
      if (charset == null || charset.isEmpty()) return keyEnc;
      return enc(keyword, charset); // charset 不支持返回 null → 保持原样
    }
    if (isArithExpr(inner)) {
      Long v = evalArith(inner, page);
      if (v != null) return String.valueOf(v);
    }
    return null;
  }

  /** 内容只含数字、page、空白和 + - * / % ( ) 且至少出现一次 page 才当算术表达式（纯常量也允许）。 */
  private static boolean isArithExpr(String inner) {
    for (int i = 0; i < inner.length(); i++) {
      char c = inner.charAt(i);
      if (Character.isDigit(c) || Character.isWhitespace(c)) continue;
      if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '(' || c == ')') continue;
      // 只许连续构成单词 page 的字母序列
      if (inner.regionMatches(true, i, "page", 0, 4)) { i += 3; continue; }
      return false;
    }
    return true;
  }

  /** 递归下降求整数算术（page 变量 + 常量 + + - * / % + 括号 + 一元负号）；失败返回 null。 */
  private static Long evalArith(String expr, long page) {
    long[] pos = {0};
    try {
      long v = arithAddSub(expr, pos, page);
      skipWs(expr, pos);
      if (pos[0] != expr.length()) return null; // 尾部有残余 → 非法
      return v;
    } catch (ArithmeticException | IllegalArgumentException e) {
      return null;
    }
  }

  private static void skipWs(String s, long[] pos) {
    while (pos[0] < s.length() && Character.isWhitespace(s.charAt((int) pos[0]))) pos[0]++;
  }

  private static long arithAddSub(String s, long[] pos, long page) {
    long v = arithMulDiv(s, pos, page);
    while (true) {
      skipWs(s, pos);
      if (pos[0] >= s.length()) return v;
      char c = s.charAt((int) pos[0]);
      if (c != '+' && c != '-') return v;
      pos[0]++;
      long r = arithMulDiv(s, pos, page);
      v = c == '+' ? v + r : v - r;
    }
  }

  private static long arithMulDiv(String s, long[] pos, long page) {
    long v = arithUnary(s, pos, page);
    while (true) {
      skipWs(s, pos);
      if (pos[0] >= s.length()) return v;
      char c = s.charAt((int) pos[0]);
      if (c != '*' && c != '/' && c != '%') return v;
      pos[0]++;
      long r = arithUnary(s, pos, page);
      if (c == '*') v = v * r;
      else {
        if (r == 0) throw new ArithmeticException("div by zero");
        v = c == '/' ? v / r : v % r;
      }
    }
  }

  private static long arithUnary(String s, long[] pos, long page) {
    skipWs(s, pos);
    if (pos[0] < s.length() && s.charAt((int) pos[0]) == '-') { pos[0]++; return -arithUnary(s, pos, page); }
    if (pos[0] < s.length() && s.charAt((int) pos[0]) == '+') { pos[0]++; return arithUnary(s, pos, page); }
    return arithAtom(s, pos, page);
  }

  private static long arithAtom(String s, long[] pos, long page) {
    skipWs(s, pos);
    if (pos[0] >= s.length()) throw new IllegalArgumentException("eof");
    char c = s.charAt((int) pos[0]);
    if (c == '(') {
      pos[0]++;
      long v = arithAddSub(s, pos, page);
      skipWs(s, pos);
      if (pos[0] >= s.length() || s.charAt((int) pos[0]) != ')') throw new IllegalArgumentException("no )");
      pos[0]++;
      return v;
    }
    if (s.regionMatches(true, (int) pos[0], "page", 0, 4)) {
      pos[0] += 4;
      return page;
    }
    if (Character.isDigit(c)) {
      long v = 0;
      while (pos[0] < s.length() && Character.isDigit(s.charAt((int) pos[0]))) {
        v = v * 10 + (s.charAt((int) pos[0]) - '0');
        pos[0]++;
      }
      return v;
    }
    throw new IllegalArgumentException("bad char " + c);
  }

  private static String enc(String v, String charset) {
    try {
      if (!java.nio.charset.Charset.isSupported(charset)) return null;
      return URLEncoder.encode(v == null ? "" : v, charset);
    } catch (Exception e) {
      return null;
    }
  }

  /** 仍含 {{...}} 或 {book.xxx} 单花括号变量 → 未解析完。 */
  private static boolean hasTemplateVar(String s) {
    if (s == null || s.isEmpty()) return false;
    if (s.contains("{{")) return true;
    if (!s.contains("{")) return false;
    return Pattern.compile("\\{[A-Za-z_][A-Za-z0-9_.]*\\}").matcher(s).find();
  }

  private static String hostOf(String base) {
    try { return new URL(base).getHost(); } catch (Exception e) { return ""; }
  }

  private static String absolutize(String url, String base) {
    if (url == null || url.isEmpty()) return base;
    String u = url.trim();
    if (u.startsWith("http://") || u.startsWith("https://")) return u;
    try { return new URL(new URL(base), u).toString(); } catch (Exception e) { return u; }
  }

  private static String extractJsonString(String json, String key) {
    String dq = "\"" + key + "\"";
    String sq = "'" + key + "'";
    for (String p : new String[] {dq, sq}) {
      int i = json.indexOf(p);
      if (i < 0) continue;
      int colon = json.indexOf(':', i + p.length());
      if (colon < 0) continue;
      int j = colon + 1;
      while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
      if (j >= json.length()) continue;
      char q = json.charAt(j);
      if (q != '"' && q != '\'') continue;
      int k = j + 1;
      StringBuilder sb = new StringBuilder();
      while (k < json.length()) {
        char c = json.charAt(k++);
        if (c == '\\' && k < json.length()) { sb.append(json.charAt(k++)); continue; }
        if (c == q) return sb.toString();
        sb.append(c);
      }
    }
    return null;
  }
}
