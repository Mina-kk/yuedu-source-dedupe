package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ResultInspectContentTest {
  @Test public void novelRequiresReadableText() {
    assertTrue(ResultInspect.inspectContent(
        "<p>这是一段足够长的小说正文内容，用来通过文本校验，还要再补一些字。</p>",
        SourceKind.NOVEL).ok);
    assertFalse(ResultInspect.inspectContent("<p>短</p>", SourceKind.NOVEL).ok);
  }

  @Test public void comicAcceptsImageUrl() {
    assertTrue(ResultInspect.inspectContent(
        "<img src=\"https://cdn.example.com/1.jpg\">",
        SourceKind.COMIC).ok);
    assertFalse(ResultInspect.inspectContent("no images here at all", SourceKind.COMIC).ok);
  }

  @Test public void videoAcceptsPlayUrl() {
    assertTrue(ResultInspect.inspectContent(
        "{\"url\":\"https://cdn.example.com/play.m3u8\"}",
        SourceKind.VIDEO).ok);
    assertFalse(ResultInspect.inspectContent("just some words", SourceKind.VIDEO).ok);
  }

  @Test public void audioAndFileAcceptMatchingSuffix() {
    assertTrue(ResultInspect.inspectContent("https://a.example/a.mp3", SourceKind.AUDIO).ok);
    assertTrue(ResultInspect.inspectContent("https://a.example/book.epub", SourceKind.FILE).ok);
  }
}
