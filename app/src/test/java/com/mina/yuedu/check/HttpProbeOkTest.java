package com.mina.yuedu.check;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpProbeOkTest {
  @Test public void twoHundredWithBodyIsOk() {
    assertTrue(HttpProbe.httpOk(200, "<html>ok</html>"));
    assertTrue(HttpProbe.httpOk(399, "{\"list\":[]}"));
  }

  @Test public void fourXxFiveXxEmptyAndCloudflareAreNotOk() {
    assertFalse(HttpProbe.httpOk(404, "not found"));
    assertFalse(HttpProbe.httpOk(500, "error"));
    assertFalse(HttpProbe.httpOk(200, ""));
    assertFalse(HttpProbe.httpOk(200, "   "));
    assertFalse(HttpProbe.httpOk(200, "<html>Just a moment... Cloudflare</html>"));
  }
}