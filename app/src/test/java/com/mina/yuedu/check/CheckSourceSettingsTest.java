package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CheckSourceSettingsTest {
  @Test public void concurrencyIsNormalizedToOneThroughOneHundred() {
    assertEquals(100, CheckSourceSettings.MAX_CONCURRENCY);

    CheckSourceSettings belowMinimum = new CheckSourceSettings();
    belowMinimum.concurrency = 0;
    belowMinimum.normalize();
    assertEquals(1, belowMinimum.concurrency);

    CheckSourceSettings atMaximum = new CheckSourceSettings();
    atMaximum.concurrency = 100;
    atMaximum.normalize();
    assertEquals(100, atMaximum.concurrency);

    CheckSourceSettings aboveMaximum = new CheckSourceSettings();
    aboveMaximum.concurrency = 101;
    aboveMaximum.normalize();
    assertEquals(100, aboveMaximum.concurrency);
  }

  @Test public void defaultConcurrencyRemainsEight() {
    CheckSourceSettings settings = new CheckSourceSettings();
    settings.normalize();
    assertEquals(8, settings.concurrency);
    assertTrue(settings.concurrency <= CheckSourceSettings.MAX_CONCURRENCY);
  }
}
