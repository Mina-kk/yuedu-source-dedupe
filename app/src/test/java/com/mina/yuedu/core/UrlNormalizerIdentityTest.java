package com.mina.yuedu.core;

import com.mina.yuedu.model.DedupeMode;
import com.mina.yuedu.model.DedupeResult;
import com.mina.yuedu.model.SourceRecord;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class UrlNormalizerIdentityTest {
  private static SourceRecord source(int order, String name, String url) {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", url);
    raw.put("ruleSearch", Collections.singletonMap("bookList", "body"));
    return new SourceRecord(order, name, url, raw);
  }

  @Test public void unicodeAndEmojiIdentityTagsRemainValid() throws Exception {
    assertEquals("https://manwane.cc/#大改",
        UrlNormalizer.standardKey("https://manwane.cc#大改"));
    assertEquals("https://tybook.taoyuewenhua.net/#🎃",
        UrlNormalizer.key("https://tybook.taoyuewenhua.net#🎃", DedupeMode.STRICT));
    assertEquals("https://so.html5.qq.com/#🎃",
        UrlNormalizer.completeKey("https://so.html5.qq.com#🎃"));
  }

  @Test public void identityTagsDistinguishSourcesButRequestUsesOrigin() throws Exception {
    String plain = "https://example.com";
    String tagged = "https://example.com#简体";
    assertNotEquals(UrlNormalizer.standardKey(plain), UrlNormalizer.standardKey(tagged));
    assertEquals("https://example.com", UrlNormalizer.requestUrl(tagged));
    assertEquals("https://example.com", UrlNormalizer.requestUrl("https://example.com#🎃"));

    DedupeResult result = DedupeEngine.run(Arrays.asList(
        source(0, "原站", plain), source(1, "简体", tagged)), DedupeMode.STANDARD, false);
    assertEquals(2, result.getRetained().size());
    assertTrue(result.getInvalid().isEmpty());
  }

  @Test public void aggressiveModeStillMergesSameHost() {
    DedupeResult result = DedupeEngine.run(Arrays.asList(
        source(0, "原站", "https://example.com"),
        source(1, "简体", "https://example.com#简体")), DedupeMode.AGGRESSIVE, false);
    assertEquals(1, result.getRetained().size());
  }
}
