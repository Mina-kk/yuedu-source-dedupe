package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * 名称相似度计算工具单元测试（移植自 MIXUULS/yuedu-source-dedupe）。
 */
public class NameSimilarityTest {

  @Test public void identicalNamesAreSimilar() {
    assertTrue(NameSimilarity.isSimilar("笔趣阁", "笔趣阁", 0.75));
    assertTrue(NameSimilarity.isSimilar("", "", 0.75));
  }

  @Test public void nullHandling() {
    assertFalse(NameSimilarity.isSimilar(null, "abc", 0.75));
    assertFalse(NameSimilarity.isSimilar("abc", null, 0.75));
    assertFalse(NameSimilarity.isSimilar(null, null, 0.75));
    assertEquals(0.0, NameSimilarity.similarity(null, "abc"), 0.001);
    assertEquals(0.0, NameSimilarity.similarity("abc", null), 0.001);
  }

  @Test public void normalizingRemovesModifiers() {
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁小说网"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁(官方)"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁（官方）"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁 官方 备用"));
  }

  @Test public void normalizingHandlesIdeographicSpace() {
    // 回归：Java 的 \s 和 trim() 都不处理全角空格(U+3000)
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁　官方"));
    assertEquals("笔趣阁", NameSimilarity.normalize("笔趣阁小说网　"));
    assertTrue(NameSimilarity.isSimilar("笔趣阁", "笔趣阁　官方", 0.75));
  }

  @Test public void similarNamesAreDetected() {
    assertTrue(NameSimilarity.isSimilar("笔趣阁", "笔趣阁小说网", 0.75));
    assertTrue(NameSimilarity.isSimilar("全本小说网", "全本小说网", 0.75));
    assertFalse(NameSimilarity.isSimilar("笔趣阁", "起点中文网", 0.75));
    assertFalse(NameSimilarity.isSimilar("全本小说网", "笔趣阁", 0.75));
  }

  @Test public void preferredKeywordDetection() {
    assertTrue(NameSimilarity.hasPreferredKeyword("笔趣阁官方"));
    assertTrue(NameSimilarity.hasPreferredKeyword("起点中文网(原版)"));
    assertFalse(NameSimilarity.hasPreferredKeyword("笔趣阁"));
    assertFalse(NameSimilarity.hasPreferredKeyword(null));
  }

  @Test public void levenshteinDistance() {
    assertEquals(0, NameSimilarity.levenshtein("", ""));
    assertEquals(3, NameSimilarity.levenshtein("abc", ""));
    assertEquals(3, NameSimilarity.levenshtein("", "abc"));
    assertEquals(0, NameSimilarity.levenshtein("abc", "abc"));
    assertEquals(1, NameSimilarity.levenshtein("abc", "abd"));
    assertEquals(1, NameSimilarity.levenshtein("abc", "ac"));
    assertEquals(2, NameSimilarity.levenshtein("abc", "a"));
  }

  @Test public void nameGroupingGroupsSimilarSources() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com"),
        source(1, "笔趣阁小说网", "https://b.com"),
        source(2, "起点中文网", "https://c.com"),
        source(3, "笔趣阁(官方)", "https://d.com")
    );
    List<List<SourceRecord>> groups = NameSimilarity.groupByName(sources);
    boolean found = false;
    for (List<SourceRecord> g : groups) {
      if (g.size() >= 3) {
        found = true;
        assertTrue(g.get(0).getName().contains("官方"));
        break;
      }
    }
    assertTrue("应找到包含3个笔趣阁相关源的组", found);
  }

  @Test public void nameGroupingKeepsDissimilarSeparate() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com"),
        source(1, "起点中文网", "https://b.com"),
        source(2, "全本小说网", "https://c.com")
    );
    assertTrue("不同名称不应分组", NameSimilarity.groupByName(sources).isEmpty());
  }

  @Test public void nameGroupingHandlesLargeLengthDifferencesWithoutFalseMatch() {
    assertFalse(NameSimilarity.isSimilar("完整书源名称甲", "完整书源名称甲以及完全不同的长长长长长长长长长后缀", 0.75));
  }

  @Test public void shortNamesMustBeExactMatch() {
    assertFalse(NameSimilarity.isSimilar("AB", "AC", 0.75));
    assertTrue(NameSimilarity.isSimilar("AB", "AB", 0.75));
    assertTrue(NameSimilarity.isSimilar("AB", "AB小说网", 0.75));
    assertFalse(NameSimilarity.isSimilar("AB", "CD小说网", 0.75));
  }

  @Test public void dedupeEngineNameModeMergesByName() {
    // 名称相似（raw.bookSourceName 相同，仅展示名不同）+ 同类型 + URL原文相同
    // + 完整 raw 深等 → 可证明是同一份源数据，折叠。
    Map<String, Object> shared = sharedRaw("笔趣阁", "https://a.com/same");
    List<SourceRecord> sources = Arrays.asList(
        new SourceRecord(0, "笔趣阁", "https://a.com/same", shared),
        new SourceRecord(1, "笔趣阁小说网", "https://a.com/same", shared),
        source(2, "起点中文网", "https://c.com")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(2, result.getRetained().size());
    assertEquals(1, result.getDuplicateGroups().size());
    assertEquals("名称相似且同类型、URL原文与全部字段完全一致才折叠（URL细节/任一字段不同或不可证的源已各自保留）",
        result.getDuplicateGroups().get(0).getReason());
  }

  @Test public void nameModeKeepsSameNameSameDomainAcrossKinds() {
    // 同名同域但类型不同（小说 vs 漫画）：同站名跨类型是不同内容 → 各自保留，不折叠。
    List<SourceRecord> sources = Arrays.asList(
        typedSource(0, "笔趣阁", "https://aaa.com/novel", 0),
        typedSource(1, "笔趣阁", "https://aaa.com/comic", 2)
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("同站名跨类型源不应被名称去重折叠", 2, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeDedupesSameNameSameFullUrlSameKind() {
    // 同名+同类型+URL原文一致+完整raw一致 → 同一份源的副本，删一个。
    List<SourceRecord> sources = Arrays.asList(
        typedSource(0, "笔趣阁", "https://aaa.com/same", 0),
        typedSource(1, "笔趣阁", "https://aaa.com/same", 0)
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(1, result.getRetained().size());
    assertEquals(1, result.getDuplicateGroups().size());
  }

  @Test public void dedupeEngineNameModeWithPreferredKeyword() {
    // 同一份源数据（raw 相同）被标了不同展示名：含"官方"关键词的优先保留。
    Map<String, Object> shared = sharedRaw("笔趣阁", "https://a.com/same");
    List<SourceRecord> sources = Arrays.asList(
        new SourceRecord(0, "笔趣阁", "https://a.com/same", shared),
        new SourceRecord(1, "笔趣阁(官方)", "https://a.com/same", shared)
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(1, result.getRetained().size());
    assertTrue(result.getRetained().get(0).getName().contains("官方"));
  }

    @Test public void dedupeEngineNameModeWithCleanNames() {
    // clean 先去装饰：同名副本清理后名称与 raw.bookSourceName 完全一致，
    // URL原文相同 → 完整身份相等，折叠。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁😀", "https://a.com/same"),
        source(1, "笔趣阁😀", "https://a.com/same")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, true);
    assertEquals(1, result.getRetained().size());
    String name = result.getRetained().get(0).getName();
    assertFalse(name.contains("😀"));
  }

  @Test public void nameModeHandlesEmptySources() {
    DedupeResult result = DedupeEngine.run(new ArrayList<SourceRecord>(), DedupeMode.NAME, false);
    assertEquals(0, result.getRetained().size());
    assertTrue(result.getInvalid().isEmpty());
  }

  @Test public void nameModeHandlesNullUrl() {
    List<SourceRecord> sources = new ArrayList<>();
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "测试源");
    sources.add(new SourceRecord(0, "测试源", null, raw));
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(0, result.getRetained().size());
    assertEquals(1, result.getInvalid().size());
  }

  @Test public void nameModeClassifiesEmptyUrlCorrectly() {
    List<SourceRecord> sources = Collections.singletonList(source(0, "测试源", "  "));
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertTrue(result.getRetained().isEmpty());
    assertEquals(1, result.getInvalid().size());
    assertEquals(com.mina.yuedu.model.InvalidSource.Kind.EMPTY_URL,
        result.getInvalid().get(0).getKind());
  }

  // ===== NAME 模式完整身份护栏回归 =====

  @Test public void nameModeKeepsSimilarNamesAcrossDifferentDomains() {
    // "笔趣阁" a.com 与 "笔趣阁" b.com 是不同站镜像 → 不跨域删，各保留。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com"),
        source(1, "笔趣阁", "https://bbb.com"),
        source(2, "笔趣阁", "https://ccc.com")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("不同域名的同名镜像源应各自保留", 3, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeKeepsSameDomainDifferentPaths() {
    // 同域但路径不同（完整URL不同）→ 不再是"同域即重复"，各自保留。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/x"),
        source(1, "笔趣阁小说网", "https://aaa.com/y")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("同域不同路径不应折叠", 2, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeKeepsDifferentPorts() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com:8080/x"),
        source(1, "笔趣阁", "https://aaa.com:9090/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("端口不同应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsDifferentQueries() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/x?api=v1"),
        source(1, "笔趣阁", "https://aaa.com/x?api=v2")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("query 不同应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsDifferentIdentityTags() {
    // #标签是书源身份的一部分：#简体 vs #大改 是不同源，不折叠。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/x#简体"),
        source(1, "笔趣阁", "https://aaa.com/x#大改")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("#身份标签不同应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsSameUrlDifferentRules() {
    // 完整URL相同但规则字段不同 → 不是同一份源，不折叠。
    List<SourceRecord> sources = Arrays.asList(
        rawSource(0, "笔趣阁", "https://aaa.com/x", "ruleA"),
        rawSource(1, "笔趣阁", "https://aaa.com/x", "ruleB")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("同URL异规则应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsUnsafeUrl() {
    // 非 HTTP(s) URL 无法证明身份 → 独成桶，不与其他源合并。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "ftp://aaa.com/x"),
        source(1, "笔趣阁", "ftp://aaa.com/x")  // 同串但非HTTP，仍不合并
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("非HTTP源不合并", 2, result.getRetained().size());
  }

  @Test public void nameModeFoldsExactDuplicate() {
    // 同一份源数据（raw 完全相同）仅展示名相似不同 → 完整身份相等，折叠。
    Map<String, Object> shared = sharedRaw("笔趣阁", "https://aaa.com/x?api=v1#简体");
    List<SourceRecord> sources = Arrays.asList(
        new SourceRecord(0, "笔趣阁", "https://aaa.com/x?api=v1#简体", shared),
        new SourceRecord(1, "笔趣阁小说网", "https://aaa.com/x?api=v1#简体", shared)
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("确切副本应折叠", 1, result.getRetained().size());
    assertEquals(1, result.getDuplicateGroups().size());
  }

  @Test public void nameModeMixedDomainGroups() {
    // 同名源：aaa.com/1 与 aaa.com/2 路径不同（完整URL不同）→ 不折叠；
    // bbb.com 跨域 → 保留。三个都保留。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/1"),
        source(1, "笔趣阁", "https://aaa.com/2"),
        source(2, "笔趣阁", "https://bbb.com")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("路径/域名不同的同名源应各自保留", 3, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeNeverLosesSources() {
    // invariant：retained + removed + invalid == 输入总数（含 normalize 后同名跨组、URL 冲突）。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://a.com/1"),
        source(1, "笔趣阁", "https://a.com/2"),
        source(2, "笔趣阁", "https://b.com"),
        source(3, "起点中文网", "https://c.com"),
        source(4, "  ", "https://d.com"),          // 名空白
        source(5, "全本小说网", "https://e.com")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    int accounted = result.getRetained().size() + result.getDuplicateCount() + result.getInvalid().size();
    assertEquals("NAME 去重不得丢源", sources.size(), accounted);
  }

  // ===== 完整 raw 等价护栏：任一业务字段不同即不折叠 =====

  @Test public void nameModeKeepsDifferentAuthHeader() {
    // 同名+同URL原文但 header（如 Authorization/Cookie）不同 → raw 不等，不折叠。
    List<SourceRecord> sources = Arrays.asList(
        sourceWith(0, "笔趣阁", "https://aaa.com/x", "header", "Bearer t1"),
        sourceWith(1, "笔趣阁", "https://aaa.com/x", "header", "Bearer t2")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("auth header 不同应保留", 2, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeKeepsHeaderVsNoHeader() {
    // 一个有 header 字段、一个没有 → raw 结构不同，不折叠。
    List<SourceRecord> sources = Arrays.asList(
        sourceWith(0, "笔趣阁", "https://aaa.com/x", "header", "Bearer t1"),
        source(1, "笔趣阁", "https://aaa.com/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("有无 header 应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsDifferentLoginUi() {
    // loginUi 字段不同（登录流程定义不同）→ 不折叠。
    List<SourceRecord> sources = Arrays.asList(
        sourceWith(0, "笔趣阁", "https://aaa.com/x", "loginUi", "form-a"),
        sourceWith(1, "笔趣阁", "https://aaa.com/x", "loginUi", "form-b")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("loginUi 不同应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsDifferentEnabled() {
    // enabled true vs false：禁用状态是源身份的一部分，不同不折叠。
    List<SourceRecord> sources = Arrays.asList(
        sourceWith(0, "笔趣阁", "https://aaa.com/x", "enabled", Boolean.TRUE),
        sourceWith(1, "笔趣阁", "https://aaa.com/x", "enabled", Boolean.FALSE)
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("enabled 不同应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsNullVsMissingField() {
    // raw 含 "loginUi":null 与完全没这个 key 是两种结构（Map.equals 不等）→ 不折叠。
    List<SourceRecord> sources = Arrays.asList(
        sourceWith(0, "笔趣阁", "https://aaa.com/x", "loginUi", null),
        source(1, "笔趣阁", "https://aaa.com/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("null 字段与缺失字段不等价，应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsNullVsEmptyStringField() {
    // null 与空串不是同一值 → 不折叠（此前字符串拼接会把两者同化）。
    List<SourceRecord> sources = Arrays.asList(
        sourceWith(0, "笔趣阁", "https://aaa.com/x", "comment", null),
        sourceWith(1, "笔趣阁", "https://aaa.com/x", "comment", "")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("null 与空串不同，应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsDifferentRawName() {
    // URL原文相同但 raw.bookSourceName 不同（数据层名字就不同）→ 不折叠，
    // 即使展示名相似。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/x"),
        source(1, "笔趣阁小说网", "https://aaa.com/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("bookSourceName 不同即不同源，应保留", 2, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  // ===== URL 原文比较：不做规范化 =====

  @Test public void nameModeKeepsTrailingSlash() {
    // /x vs /x/ ：原文不同即不同身份（规范化会归并，但原文比较不归并）。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/x"),
        source(1, "笔趣阁", "https://aaa.com/x/")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("尾斜杠差异应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsExplicitDefaultPort() {
    // https://a.com vs https://a.com:443 ：显式默认端口原文不同 → 保留。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "https://aaa.com/x"),
        source(1, "笔趣阁", "https://aaa.com:443/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("显式默认端口差异应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsHttpVsHttps() {
    // scheme 不同原文不同 → 保留。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "http://aaa.com/x"),
        source(1, "笔趣阁", "https://aaa.com/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("http/https 应保留", 2, result.getRetained().size());
  }

  @Test public void nameModeKeepsUnsafeUrlDifferentOrders() {
    // 非 HTTP(s) 同 URL 不同 order：每条独占 opaque key，绝不合并 → 都保留。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "javascript:abc"),
        source(1, "笔趣阁", "javascript:abc"),
        source(2, "笔趣阁", "javascript:abc")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("非HTTP源（不同order）绝不合并", 3, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeKeepsUnsafeUrlSameOrder() {
    // 回归：即使两个非 HTTP(s) 源 URL 相同、order 相同、raw 也完全相同，
    // opaque key 仍互不相等 → 绝不折叠（旧的 kind+url+order 键会误并）。
    Map<String, Object> shared = sharedRaw("笔趣阁", "ftp://aaa.com/x");
    List<SourceRecord> sources = Arrays.asList(
        new SourceRecord(0, "笔趣阁", "ftp://aaa.com/x", shared),
        new SourceRecord(0, "笔趣阁小说网", "ftp://aaa.com/x", shared)  // 同 order 同 URL
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals("同URL同order的非HTTP源也不得合并", 2, result.getRetained().size());
    assertEquals(0, result.getDuplicateGroups().size());
  }

  @Test public void nameModeKeepsUnsafeVsHttp() {
    // 同名字符串相似，一个非HTTP一个HTTP → 不合并。
    List<SourceRecord> sources = Arrays.asList(
        source(0, "笔趣阁", "ftp://aaa.com/x"),
        source(1, "笔趣阁", "https://aaa.com/x")
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.NAME, false);
    assertEquals(2, result.getRetained().size());
  }

  @Test public void standardModeNeverLosesSources() {
    List<SourceRecord> sources = Arrays.asList(
        source(0, "甲", "https://a.com/1"),
        source(1, "乙", "https://a.com/1"),   // 同 URL
        source(2, "丙", "https://b.com"),
        source(3, "丁", "  "),               // 空 URL → invalid
        source(4, "戊", null)                 // null URL → invalid
    );
    DedupeResult result = DedupeEngine.run(sources, DedupeMode.STANDARD, false);
    int accounted = result.getRetained().size() + result.getDuplicateCount() + result.getInvalid().size();
    assertEquals(sources.size(), accounted);
  }

  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(order, name, url, raw);
  }

  /** 在 source() 基础上追加一个额外 raw 字段（可为 null）。 */
  private static SourceRecord sourceWith(int order, String name, String url, String key, Object val) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    raw.put(key, val);
    return new SourceRecord(order, name, url, raw);
  }

  /**
   * 返回一份固定 bookSourceName 的 raw，供多个 SourceRecord 共享内容：
   * 展示名可与 raw 内名字不同，模拟"同一份源数据被标了不同展示名"。
   */
  private static Map<String, Object> sharedRaw(String rawName, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", rawName);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return raw;
  }

  private static SourceRecord typedSource(int order, String name, String url, int type) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("bookSourceType", type);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(order, name, url, raw);
  }

  /** 自定义 ruleSearch 值的源（其余字段与 source() 相同），用于"同URL异规则"用例。 */
  private static SourceRecord rawSource(int order, String name, String url, Object ruleSearch) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", ruleSearch);
    return new SourceRecord(order, name, url, raw);
  }
}
