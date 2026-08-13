package com.mina.yuedu.check;

import com.mina.yuedu.model.SourceRecord;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SourceKindTest {
  @Test public void officialInts() {
    assertEquals(SourceKind.AUDIO, SourceKind.of(src("a", 1, null)));
    assertEquals(SourceKind.COMIC, SourceKind.of(src("a", 2, null)));
    assertEquals(SourceKind.FILE, SourceKind.of(src("a", 3, null)));
    assertEquals(SourceKind.VIDEO, SourceKind.of(src("a", 4, null)));
  }

  @Test public void defaultZeroIsNovelUnlessHint() {
    assertEquals(SourceKind.NOVEL, SourceKind.of(src("普通源", 0, null)));
    assertEquals(SourceKind.VIDEO, SourceKind.of(src("某某影视", 0, "视频")));
    assertEquals(SourceKind.COMIC, SourceKind.of(src("某某漫画", 0, null)));
    assertEquals(SourceKind.NOVEL, SourceKind.of(src("看起来像漫画", 0, "小说")));
  }

  @Test public void explicitTypeWinsOverName() {
    assertEquals(SourceKind.COMIC, SourceKind.of(src("小说站", 2, "小说")));
    assertEquals(SourceKind.AUDIO, SourceKind.of(src("某某漫画", 1, null)));
    assertEquals(SourceKind.VIDEO, SourceKind.of(src("普通源", "video", null)));
  }

  @Test public void filterKeepsOnlySelectedKinds() {
    List<SourceRecord> all = java.util.Arrays.asList(
        src("普通小说", 0, null),
        src("某漫", 2, null),
        src("某影", 4, null));
    Map<SourceKind, Integer> counts = SourceKind.counts(all);
    assertEquals(Integer.valueOf(1), counts.get(SourceKind.NOVEL));
    assertEquals(Integer.valueOf(1), counts.get(SourceKind.COMIC));
    assertEquals(Integer.valueOf(1), counts.get(SourceKind.VIDEO));
    List<SourceRecord> novels = SourceKind.filter(all, java.util.Collections.singleton(SourceKind.NOVEL));
    assertEquals(1, novels.size());
    assertEquals("普通小说", novels.get(0).getName());
    assertEquals(0, SourceKind.filter(all, java.util.Collections.<SourceKind>emptySet()).size());
  }

  @Test public void contentFailGroupMatchesKind() {
    assertEquals("正文失效", SourceKind.NOVEL.contentFailGroup());
    assertEquals("图片失效", SourceKind.COMIC.contentFailGroup());
    assertEquals("播放失效", SourceKind.VIDEO.contentFailGroup());
    assertEquals("音频失效", SourceKind.AUDIO.contentFailGroup());
    assertEquals("下载失效", SourceKind.FILE.contentFailGroup());
  }

  private static SourceRecord src(String name, Object type, String group) {
    Map<String, Object> raw = new LinkedHashMap<String, Object>();
    raw.put("bookSourceName", name);
    raw.put("bookSourceUrl", "https://example.com");
    if (type != null) raw.put("bookSourceType", type);
    if (group != null) raw.put("bookSourceGroup", group);
    return new SourceRecord(0, name, "https://example.com", raw);
  }
}
