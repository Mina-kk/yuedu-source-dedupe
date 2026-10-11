package com.mina.yuedu.check;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** AnalyzeUrlLite book/上下文变量替换：book.name/author URL 编码、book.bookUrl 原样、
 *  未知 {{...}} 置 unresolved、body 内替换、looksLikeUrlTemplate 判定。 */
public class AnalyzeUrlLiteBookVarTest {

  private static Map<String, String> vars(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    return m;
  }

  @Test public void bookNameEncodedFromVars() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{book.name}}", "https://x.com", "关键词", 1,
        vars("book.name", "我的 书"));
    // URLEncoder: 空格→+，中文 UTF-8 编码
    assertTrue("got=" + r.url, r.url.startsWith("https://x.com/s?q="));
    assertTrue("got=" + r.url, r.url.contains("%E6%88%91%E7%9A%84"));
    assertFalse(r.unresolved);
  }

  @Test public void bookNameFallsBackToKeyword() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{book.name}}", "https://x.com", "我的", 1, null);
    assertTrue("got=" + r.url, r.url.contains("%E6%88%91%E7%9A%84"));
    assertFalse(r.unresolved);
  }

  @Test public void bookVarWithSpacesAroundName() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{ book.name }}&a={{book.author}}", "https://x.com", "kw", 1,
        vars("book.author", "作者甲"));
    assertTrue("got=" + r.url, r.url.contains("q=kw")); // keyword "kw" 无需编码
    assertTrue("got=" + r.url, r.url.contains("a=" + encAuthor()));
    assertFalse(r.unresolved);
  }

  private static String encAuthor() {
    try {
      return java.net.URLEncoder.encode("作者甲", "UTF-8");
    } catch (Exception e) {
      return "作者甲";
    }
  }

  @Test public void bookUrlInsertedVerbatim() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "{{book.bookUrl}}", "https://x.com", "kw", 1,
        vars("book.bookUrl", "https://x.com/book/123?a=b&c=d"));
    assertEquals("https://x.com/book/123?a=b&c=d", r.url);
    assertFalse(r.unresolved);
  }

  @Test public void bookUrlRelativeToBase() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "{{book.bookUrl}}", "https://x.com/dir/", "kw", 1,
        vars("book.bookUrl", "/book/9"));
    assertEquals("https://x.com/book/9", r.url);
    assertFalse(r.unresolved);
  }

  @Test public void unknownExpressionMarksUnresolved() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{java.ajax('x')}}", "https://x.com", "kw", 1, null);
    assertTrue(r.unresolved);
  }

  @Test public void singleBraceBookVarMarksUnresolvedWhenMissing() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={book.name}", "https://x.com", "kw", 1,
        vars("book.name", "有值"));
    assertFalse("vars 有值应已替换: " + r.url, r.unresolved);
    AnalyzeUrlLite r2 = AnalyzeUrlLite.parse(
        "https://x.com/s?q={book.bookUrl}", "https://x.com", "kw", 1, null);
    assertTrue(r2.unresolved);
  }

  @Test public void bodyVarsReplaced() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s,{\"method\":\"POST\",\"body\":\"k={{book.name}}&u={{book.bookUrl}}\"}",
        "https://x.com", "kw", 1,
        vars("book.name", "名", "book.bookUrl", "https://x.com/b/1"));
    assertEquals("POST", r.method);
    assertTrue("body=" + r.body, r.body.contains("u=https://x.com/b/1"));
    assertFalse(r.unresolved);
  }

  @Test public void bodyUnresolvedPropagates() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s,{\"method\":\"POST\",\"body\":\"k={{java.ajax('x')}}\"}",
        "https://x.com", "kw", 1, null);
    assertTrue(r.unresolved);
  }

  @Test public void looksLikeUrlTemplateJudgement() {
    assertFalse(AnalyzeUrlLite.looksLikeUrlTemplate(".read@href"));   // CSS 提取规则
    assertFalse(AnalyzeUrlLite.looksLikeUrlTemplate("//a/@href"));    // XPath
    assertTrue(AnalyzeUrlLite.looksLikeUrlTemplate("/list/{{book.bookUrl}}")); // 站内路径模板
    assertTrue(AnalyzeUrlLite.looksLikeUrlTemplate("https://x"));     // 完整 URL
    assertTrue(AnalyzeUrlLite.looksLikeUrlTemplate("  https://x/y "));// trim 后 http
    assertFalse(AnalyzeUrlLite.looksLikeUrlTemplate(null));
    assertFalse(AnalyzeUrlLite.looksLikeUrlTemplate("  "));
  }

  @Test public void originAndSourceVarsFallbackToBase() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "{{book.origin}}/s?src={{source.bookSourceUrl}}&k={{source.key}}&b={{baseUrl}}",
        "https://x.com", "kw", 1, null);
    assertTrue("got=" + r.url, r.url.startsWith("https://x.com/s?src="));
    assertFalse(r.unresolved);
  }

  @Test public void tocUrlFromVars() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "{{book.tocUrl}}", "https://x.com", "kw", 1,
        vars("book.tocUrl", "https://x.com/toc/1"));
    assertEquals("https://x.com/toc/1", r.url);
    assertFalse(r.unresolved);
  }

  // ---- {{...}} 安全求值：key/page 空白、算术、encode 函数 ----

  @Test public void keyPageWithSpaces() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{ key }}&p={{ page }}", "https://x.com", "kw", 3, null);
    assertTrue("got=" + r.url, r.url.contains("q=kw"));
    assertTrue("got=" + r.url, r.url.contains("p=3"));
    assertFalse(r.unresolved);
  }

  @Test public void arithPageMinusOneTimes20() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?start={{(page-1)*20}}", "https://x.com", "kw", 3, null);
    assertTrue("got=" + r.url, r.url.contains("start=40"));
    assertFalse(r.unresolved);
  }

  @Test public void arithPageTimes10() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?o={{page*10}}", "https://x.com", "kw", 2, null);
    assertTrue("got=" + r.url, r.url.contains("o=20"));
    assertFalse(r.unresolved);
  }

  @Test public void arithPageMinusOneSpaced() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?p={{ page - 1 }}", "https://x.com", "kw", 5, null);
    assertTrue("got=" + r.url, r.url.contains("p=4"));
    assertFalse(r.unresolved);
  }

  @Test public void arithDivModAndConstants() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?a={{page/2}}&b={{page%2}}&c={{10+5}}", "https://x.com", "kw", 5, null);
    assertTrue("got=" + r.url, r.url.contains("a=2"));   // 5/2 整除
    assertTrue("got=" + r.url, r.url.contains("b=1"));   // 5%2
    assertTrue("got=" + r.url, r.url.contains("c=15"));  // 纯常量
    assertFalse(r.unresolved);
  }

  @Test public void arithDivByZeroStaysUnresolved() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?p={{page/0}}", "https://x.com", "kw", 1, null);
    assertTrue(r.unresolved);
    assertTrue("原样保留: " + r.url, r.url.contains("{{page/0}}"));
  }

  @Test public void arithInvalidExprStaysUnresolved() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?p={{page+*2}}", "https://x.com", "kw", 1, null);
    assertTrue(r.unresolved);
    // 非法但按字符白名单放行的表达式求值失败 → 原样保留
    assertTrue("got=" + r.url, r.url.contains("{{page+*2}}"));
    // 含外来标识符的表达式：isArithExpr 拒绝 → 保持 unresolved
    AnalyzeUrlLite r2 = AnalyzeUrlLite.parse(
        "https://x.com/s?p={{foo+1}}", "https://x.com", "kw", 1, null);
    assertTrue(r2.unresolved);
  }

  @Test public void javaEncodeUriUtf8() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{java.encodeURI(key)}}", "https://x.com", "我的", 1, null);
    assertTrue("got=" + r.url, r.url.contains("%E6%88%91%E7%9A%84"));
    assertFalse(r.unresolved);
  }

  @Test public void javaEncodeUriGbkDoubleAndSingleQuote() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{java.encodeURI(key,\"gbk\")}}", "https://x.com", "我的", 1, null);
    assertTrue("GBK 编码: got=" + r.url, r.url.contains("%CE%D2%B5%C4"));
    assertFalse(r.unresolved);
    AnalyzeUrlLite r2 = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{java.encodeURI(key,'GBK')}}", "https://x.com", "我的", 1, null);
    assertTrue("单引号大写: got=" + r2.url, r2.url.contains("%CE%D2%B5%C4"));
    assertFalse(r2.unresolved);
  }

  @Test public void javaEncodeUriUnsupportedCharsetStaysUnresolved() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{java.encodeURI(key,\"no-such-cs\")}}", "https://x.com", "kw", 1, null);
    assertTrue(r.unresolved);
  }

  @Test public void encodeURIComponentAndEncodeURIPlain() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?a={{encodeURIComponent(key)}}&b={{encodeURI(key)}}",
        "https://x.com", "我的", 1, null);
    assertTrue("got=" + r.url, r.url.contains("a=%E6%88%91%E7%9A%84"));
    assertTrue("got=" + r.url, r.url.contains("b=%E6%88%91%E7%9A%84"));
    assertFalse(r.unresolved);
  }

  @Test public void exprsResolvedInBody() {
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s,{\"method\":\"POST\",\"body\":\"k={{java.encodeURI(key)}}&p={{page-1}}\"}",
        "https://x.com", "我的", 4, null);
    assertTrue("body=" + r.body, r.body.contains("k=%E6%88%91%E7%9A%84"));
    assertTrue("body=" + r.body, r.body.contains("p=3"));
    assertFalse(r.unresolved);
  }

  @Test public void unresolvedStaysForUnmatchedExpr() {
    // {{java.ajax('x')}} 这类函数仍不解析 → unresolved
    AnalyzeUrlLite r = AnalyzeUrlLite.parse(
        "https://x.com/s?q={{java.ajax('x')}}", "https://x.com", "kw", 1, null);
    assertTrue(r.unresolved);
  }
}
