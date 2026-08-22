package com.mina.yuedu.network;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FetchPolicyTest {
  @Test public void hostKeyStripsWwwAndPortDefault() {
    assertEquals("yckceo.com", FetchPolicy.hostKey("https://www.yckceo.com/yuedu/shuyuans/json/id/1228.json"));
    assertEquals("yckceo.com", FetchPolicy.hostKey("https://yckceo.com/a.json"));
    assertEquals("yckceo.com", FetchPolicy.hostKey("http://WWW.YCKCEO.COM/b.json"));
  }

  @Test public void differentHostsStayDistinct() {
    assertEquals("a.example", FetchPolicy.hostKey("https://a.example/1.json"));
    assertEquals("b.example", FetchPolicy.hostKey("https://b.example/1.json"));
  }

  @Test public void originForReferer() {
    assertEquals("https://www.yckceo.com/", FetchPolicy.origin("https://www.yckceo.com/yuedu/x.json"));
  }

  @Test public void retryTransientButNotHardHttp() {
    assertTrue(FetchPolicy.isRetryable(new SocketTimeoutException("read timed out")));
    assertTrue(FetchPolicy.isRetryable(new ConnectException("failed to connect")));
    assertTrue(FetchPolicy.isRetryable(new SocketException("Connection reset")));
    assertFalse(FetchPolicy.isRetryable(new UnknownHostException("dns")));
    assertTrue(FetchPolicy.isRetryable(new java.io.IOException("HTTP 429")));
    assertTrue(FetchPolicy.isRetryable(new java.io.IOException("HTTP 503")));
    assertTrue(FetchPolicy.isRetryable(new java.io.IOException("empty body")));
    assertFalse(FetchPolicy.isRetryable(new java.io.IOException("HTTP 404")));
    assertFalse(FetchPolicy.isRetryable(new java.io.IOException("HTTP 403")));
    assertFalse(FetchPolicy.isRetryable(new java.io.IOException("HTTP 400")));
    assertFalse(FetchPolicy.isRetryable(new java.io.IOException("response too large (1)")));
    assertFalse(FetchPolicy.isRetryable(null));
  }

  @Test public void backoffGrowsButStaysShort() {
    assertEquals(400L, FetchPolicy.backoffMs(1));
    assertEquals(1200L, FetchPolicy.backoffMs(2));
    assertTrue(FetchPolicy.backoffMs(2) < 3_000L);
  }
}
