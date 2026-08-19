package com.mina.yuedu.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ElapsedClockTest {
  @Test public void ticksWithoutWaitingForASourceCallback() {
    ElapsedClock clock = new ElapsedClock();
    clock.start(1_000L);
    assertTrue(clock.isRunning());
    assertEquals(2_500L, clock.elapsed(3_500L));
    assertEquals("2 秒", clock.format(3_500L));
    clock.stop(4_200L);
    assertFalse(clock.isRunning());
    assertEquals(3_200L, clock.elapsed(99_000L));
  }

  @Test public void resetClears() {
    ElapsedClock clock = new ElapsedClock();
    clock.start(10L);
    clock.stop(110L);
    clock.reset();
    assertEquals(0L, clock.elapsed(1_000L));
    assertFalse(clock.isRunning());
  }
}
