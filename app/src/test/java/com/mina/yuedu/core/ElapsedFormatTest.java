package com.mina.yuedu.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ElapsedFormatTest {
  @Test public void formatsMinutesAndSeconds() {
    assertEquals("0 毫秒", ElapsedFormat.format(0));
    assertEquals("12 秒", ElapsedFormat.format(12_000));
    assertEquals("2 分 5 秒", ElapsedFormat.format(125_000));
    assertEquals("1 小时 1 分 1 秒", ElapsedFormat.format(3_661_000));
  }
}
