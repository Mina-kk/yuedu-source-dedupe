package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CheckSourceSettingsTest {
  @Test public void concurrencyIsNormalizedToOneThroughFiveHundred() {
    assertEquals(500, CheckSourceSettings.MAX_CONCURRENCY);

    CheckSourceSettings belowMinimum = new CheckSourceSettings();
    belowMinimum.concurrency = 0;
    belowMinimum.normalize();
    assertEquals(1, belowMinimum.concurrency);

    CheckSourceSettings atMaximum = new CheckSourceSettings();
    atMaximum.concurrency = 500;
    atMaximum.normalize();
    assertEquals(500, atMaximum.concurrency);

    CheckSourceSettings aboveMaximum = new CheckSourceSettings();
    aboveMaximum.concurrency = 501;
    aboveMaximum.normalize();
    assertEquals(500, aboveMaximum.concurrency);
  }

  @Test public void defaultConcurrencyRemainsEight() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.normalize();
    assertEquals(8, settings.concurrency);
    assertTrue(settings.concurrency <= CheckSourceSettings.MAX_CONCURRENCY);
  }

  @Test public void probeRoundsDefaultAndCapAtTen() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.normalize();
    assertEquals(10, settings.probeRounds);
    assertEquals(10, CheckSourceSettings.DEFAULT_PROBE_ROUNDS);
    assertEquals(10, CheckSourceSettings.MAX_PROBE_ROUNDS);

    CheckSourceSettings zero = new CheckSourceSettings();
    zero.probeRounds = 0;
    zero.normalize();
    assertEquals(1, zero.probeRounds);

    CheckSourceSettings over = new CheckSourceSettings();
    over.probeRounds = 11;
    over.normalize();
    assertEquals(10, over.probeRounds);
  }

  @Test public void blankKeywordFallsBackToDefault() {
    CheckSourceSettings empty = new CheckSourceSettings();
    empty.keyword = "";
    empty.normalize();
    assertEquals("我的", empty.keyword);

    CheckSourceSettings spaces = new CheckSourceSettings();
    spaces.keyword = "   ";
    spaces.normalize();
    assertEquals("我的", spaces.keyword);

    CheckSourceSettings nul = new CheckSourceSettings();
    nul.keyword = null;
    nul.normalize();
    assertEquals("我的", nul.keyword);
  }

  @Test public void customKeywordIsTrimmedAndKept() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.keyword = "  斗破苍穹  ";
    settings.normalize();
    assertEquals("斗破苍穹", settings.keyword);
  }

  @Test public void allKindFiltersOffRestoreAllOn() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.checkNovel = false;
    settings.checkComic = false;
    settings.checkVideo = false;
    settings.checkAudio = false;
    settings.checkFile = false;
    settings.normalize();
    assertTrue(settings.checkNovel);
    assertTrue(settings.checkComic);
    assertTrue(settings.checkVideo);
    assertTrue(settings.checkAudio);
    assertTrue(settings.checkFile);
  }

  @Test public void allowsHonorsKindFilters() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.checkNovel = false;
    settings.checkComic = true;
    settings.checkVideo = false;
    settings.checkAudio = true;
    settings.checkFile = false;
    settings.normalize();
    assertFalse(settings.allows(SourceKind.NOVEL));
    assertTrue(settings.allows(SourceKind.COMIC));
    assertFalse(settings.allows(SourceKind.VIDEO));
    assertTrue(settings.allows(SourceKind.AUDIO));
    assertFalse(settings.allows(SourceKind.FILE));
  }

  @Test public void resetToDefaultsRestoresKeywordTimeoutAndKinds() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.timeoutSeconds = 30;
    settings.concurrency = 500;
    settings.probeRounds = 3;
    settings.keyword = "斗破苍穹";
    settings.checkSearch = false;
    settings.checkNovel = false;
    settings.checkVideo = false;
    settings.resetToDefaults();
    assertEquals(180, settings.timeoutSeconds);
    assertEquals(8, settings.concurrency);
    assertEquals(10, settings.probeRounds);
    assertEquals("我的", settings.keyword);
    assertTrue(settings.checkSearch);
    assertTrue(settings.checkNovel);
    assertTrue(settings.checkVideo);
    assertTrue(settings.checkComic);
  }

  // ===== 「快速准确」预设 =====

  @Test public void quickAccuratePresetAppliesFastParams() {
    CheckSourceSettings s = new CheckSourceSettings();
    s.probeRounds = 10;
    s.timeoutSeconds = 300;
    s.applyQuickAccurate();
    assertTrue(s.quickAccurate);
    assertTrue(s.quickMode);
    assertEquals(3, s.probeRounds);
    assertEquals(60L, s.timeoutSeconds);
    assertTrue(s.checkSearch);
    assertTrue(s.checkInfo);
    assertFalse(s.checkDiscovery);
    assertFalse(s.checkCategory);
    assertFalse(s.checkContent);
  }

  @Test public void quickAccurateKeepsCleanupFlagsUntouched() {
    // 预设绝不动 deleteCaptcha/cleanLogin —— 用户清理语义不受一键入口影响。
    CheckSourceSettings s = new CheckSourceSettings();
    s.deleteCaptcha = false;
    s.cleanLogin = false;
    s.keyword = "斗破苍穹";
    s.okStatusRanges = "200,403";
    s.applyQuickAccurate();
    assertFalse(s.deleteCaptcha);
    assertFalse(s.cleanLogin);
    assertEquals("斗破苍穹", s.keyword);
    assertEquals("200,403", s.okStatusRanges);
  }

  @Test public void quickAccurateOddRoundsPreventTie() {
    // 3 轮：过半通过需 ≥2 成功，无平票；2 轮时 1 成功即不过半（success*2 > n 为 2>2 假）。
    assertTrue(CheckAggregator.decided(3, 2, 2));
    assertFalse(CheckAggregator.decided(3, 1, 1));
    assertEquals(3, CheckSourceSettings.QUICK_PROBE_ROUNDS);
    assertEquals(1, CheckSourceSettings.QUICK_PROBE_ROUNDS % 2);
  }

  @Test public void quickAccurateFlagRoundTripsThroughCopy() {
    CheckSourceSettings s = new CheckSourceSettings();
    s.applyQuickAccurate();
    CheckSourceSettings c = s.copy();
    assertTrue(c.quickAccurate);
    assertEquals(3, c.probeRounds);
  }
}