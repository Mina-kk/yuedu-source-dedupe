package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 校验引擎 uncovered 语义：启用步骤有规则但链路 URL 缺失 → 证据不足标"待复核"，
 *  既不算全链路成功，也不当失败误删（SKIPPED 在导出过滤下会被保留）。 */
public class CheckSourceUncoveredTest {

  /** 循环回环服务：对每个请求都回 200 + body。 */
  private static String serveForever(String body) throws Exception {
    final ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    Thread t = new Thread(() -> {
      try {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        while (true) {
          Socket s;
          try { s = ss.accept(); } catch (Exception e) { return; }
          try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            int r, nl = 0;
            while ((r = s.getInputStream().read()) >= 0) {
              buf.write(r);
              if (r == '\n') { if (nl == 1) break; nl = 1; } else if (r != '\r') nl = 0;
            }
            String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n";
            OutputStream os = s.getOutputStream();
            os.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            os.write(b); os.flush(); s.close();
          } catch (Exception ignored) {}
        }
      } catch (Exception ignored) {}
    });
    t.setDaemon(true); t.start();
    return "http://127.0.0.1:" + ss.getLocalPort();
  }

  private static SourceRecord sourceWithRules(String url, boolean withRules) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", "测试源");
    raw.put("bookSourceUrl", url);
    if (withRules) {
      raw.put("ruleBookInfo", Collections.singletonMap("name", "h1"));
      raw.put("ruleToc", Collections.singletonMap("chapterList", "a"));
      raw.put("ruleContent", Collections.singletonMap("content", "p"));
    }
    return new SourceRecord(0, "测试源", url, raw);
  }

  @Test public void enabledStepsWithRuleButNoUrlAreUncoveredNotSuccess() throws Exception {
    // checkSearch 关（不产生 bookUrl）、checkInfo/Category/Content 开且源有规则
    // → 详情/目录/正文整步没跑到（uncovered），不能算"校验成功"。
    String base = serveForever("<html><body>ok page</body></html>");
    SourceRecord s = sourceWithRules(base, true);
    CheckSourceSettings st = new CheckSourceSettings();
    st.probeRounds = 1;
    st.checkSearch = false;      // 搜索不跑 → bookUrl 恒 null
    st.checkDiscovery = true;    // 发现开但 exploreUrl 空 → 跳过不报错（normalize 要求至少留一入口）
    st.checkInfo = true;         // 详情开 + 有规则 + bookUrl null → uncovered
    st.checkCategory = true;
    st.checkContent = true;
    CheckSourceEngine engine = new CheckSourceEngine(1);
    CheckSourceResult r = engine.checkOne(s, st);
    engine.close();
    // 有未覆盖步骤 → 不算 SUCCESS，也不当 FAILED 误删：SKIPPED 待复核保留
    assertEquals(CheckSourceResult.Status.SKIPPED, r.status);
    assertFalse(r.isUsable());
    // 未覆盖标记透出（"详情未覆盖"等）且带"待复核"标记
    boolean hasUncovered = false, hasReview = false;
    for (String g : r.groups) {
      if (g != null && g.endsWith("未覆盖")) hasUncovered = true;
      if ("待复核".equals(g)) hasReview = true;
    }
    assertTrue("应透出未覆盖步骤标记, got=" + r.groups, hasUncovered);
    assertTrue("应透出待复核标记, got=" + r.groups, hasReview);
  }

  @Test public void uncoveredRoundAggregatesToSkippedNotFailed() throws Exception {
    // 多轮探测每轮都"步骤全过但覆盖不全" → 聚合后仍 SKIPPED 待复核，不升级成 FAILED。
    String base = serveForever("<html><body>ok page</body></html>");
    SourceRecord s = sourceWithRules(base, true);
    CheckSourceSettings st = new CheckSourceSettings();
    st.probeRounds = 3;
    st.checkSearch = false;
    st.checkDiscovery = true;
    st.checkInfo = true;
    st.checkCategory = true;
    st.checkContent = true;
    CheckSourceEngine engine = new CheckSourceEngine(1);
    CheckSourceResult r = engine.checkOne(s, st);
    engine.close();
    assertEquals(CheckSourceResult.Status.SKIPPED, r.status);
    assertFalse(r.isUsable());
  }

  @Test public void enabledStepsWithoutRuleAndNoUrlStillFailNotSilent() throws Exception {
    // checkInfo 开 + 无规则 + bookUrl null → 详情失效（原有逻辑保留，不误放）
    String base = serveForever("<html><body>ok</body></html>");
    SourceRecord s = sourceWithRules(base, false); // 无规则
    CheckSourceSettings st = new CheckSourceSettings();
    st.probeRounds = 1;
    st.checkSearch = false;
    st.checkDiscovery = true;    // 留入口，normalize 不重开搜索
    st.checkInfo = true;
    st.checkCategory = false;
    st.checkContent = false;
    CheckSourceEngine engine = new CheckSourceEngine(1);
    CheckSourceResult r = engine.checkOne(s, st);
    engine.close();
    assertEquals(CheckSourceResult.Status.FAILED, r.status);
    assertTrue(r.groups.contains("详情失效"));
  }
}
