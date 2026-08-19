package com.mina.yuedu.network;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SourceParserUnwrapTest {
  @Test public void bomAndHtmlWrapperStillParseThreeSources() {
    String json = "\uFEFF<pre>["
        + "{\"bookSourceName\":\"一\",\"bookSourceUrl\":\"https://a.example\"},"
        + "{\"bookSourceName\":\"二\",\"bookSourceUrl\":\"https://b.example\"},"
        + "{\"bookSourceName\":\"三\",\"bookSourceUrl\":\"https://c.example\"}"
        + "]</pre>";
    SourceParser.ParseResult p = SourceParser.parseArray(json, 0);
    assertEquals(3, p.getRecords().size());
    assertEquals("三", p.getRecords().get(2).getName());
  }

  @Test public void jsonHijackingPrefix() {
    String json = ")]}'\n[{\"bookSourceName\":\"一\",\"bookSourceUrl\":\"https://a.example\"}]";
    SourceParser.ParseResult p = SourceParser.parseArray(json, 0);
    assertEquals(1, p.getRecords().size());
    assertTrue(p.getInvalid().isEmpty());
  }
}