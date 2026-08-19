package com.mina.yuedu.core;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UrlInputParserTest {
  @Test public void threeUrlsOnSeparateLines() {
    List<String> urls = UrlInputParser.parse(
        "https://a.example/1.json\nhttps://b.example/2.json\nhttps://c.example/3.json");
    assertEquals(3, urls.size());
    assertEquals("https://a.example/1.json", urls.get(0));
    assertEquals("https://c.example/3.json", urls.get(2));
  }

  @Test public void threeUrlsPastedOnOneLine() {
    List<String> urls = UrlInputParser.parse(
        "https://a.example/1.json https://b.example/2.json https://c.example/3.json");
    assertEquals(3, urls.size());
  }

  @Test public void chineseCommaAndFullWidthSpace() {
    List<String> urls = UrlInputParser.parse(
        "https://a.example/1.json，https://b.example/2.json\u3000https://c.example/3.json");
    assertEquals(3, urls.size());
  }

  @Test public void dedupesSameUrl() {
    List<String> urls = UrlInputParser.parse(
        "https://a.example/1.json\nhttps://a.example/1.json");
    assertEquals(1, urls.size());
  }

  @Test public void emptyAndNull() {
    assertTrue(UrlInputParser.parse(null).isEmpty());
    assertTrue(UrlInputParser.parse("   \n  ").isEmpty());
  }
}
