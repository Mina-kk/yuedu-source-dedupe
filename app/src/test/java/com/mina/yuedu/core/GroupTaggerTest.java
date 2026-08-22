package com.mina.yuedu.core;

import com.mina.yuedu.check.SourceKind;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupTaggerTest {
  @Test public void appendsDateKindCountWithoutTouchingOriginal() {
    assertEquals("优质,大灰狼,✔2026-8-22小说（120）",
        GroupTagger.apply("优质,大灰狼", SourceKind.NOVEL, 120, "2026-8-22"));
  }

  @Test public void emptyOriginalBecomesOnlyTypeStamp() {
    assertEquals("✔2026-8-22漫画（18）",
        GroupTagger.apply("", SourceKind.COMIC, 18, "2026-8-22"));
    assertEquals("✔2026-8-22音频（5）",
        GroupTagger.apply(null, SourceKind.AUDIO, 5, "2026-8-22"));
  }

  @Test public void stripsLegacyQualityAndOldTypeStamps() {
    String old = "优质,✔2026-8-21检验去重（优质99）,✔2026-8-21小说（50）";
    assertEquals("优质,✔2026-8-22小说（120）",
        GroupTagger.apply(old, SourceKind.NOVEL, 120, "2026-8-22"));
  }

  @Test public void keepsLiteralNovelNameInOriginalGroup() {
    assertEquals("优质小说,✔2026-8-22小说（3）",
        GroupTagger.apply("优质小说", SourceKind.NOVEL, 3, "2026-8-22"));
  }

  @Test public void typeStampFormat() {
    assertEquals("✔2026-8-22视频（6）", GroupTagger.typeStamp(SourceKind.VIDEO, 6, "2026-8-22"));
    assertEquals("✔2026-8-22文件（2）", GroupTagger.typeStamp(SourceKind.FILE, 2, "2026-8-22"));
    assertTrue(GroupTagger.isStamp("✔2026-8-22小说（120）"));
    assertTrue(GroupTagger.isStamp("✔2026-8-22检验去重（优质99）"));
    assertFalse(GroupTagger.isStamp("优质"));
    assertFalse(GroupTagger.isStamp("小说"));
  }
}
