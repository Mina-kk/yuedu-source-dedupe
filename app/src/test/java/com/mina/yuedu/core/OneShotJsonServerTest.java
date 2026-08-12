package com.mina.yuedu.core;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class OneShotJsonServerTest {
  @Test public void servesUtf8JsonOnceAndThenCloses() throws Exception {
    String json = "[{\"bookSourceName\":\"测试🎃\",\"bookSourceUrl\":\"https://example.com/#简体\"}]";
    OneShotJsonServer server = OneShotJsonServer.start(json, 5_000);
    assertTrue(server.getUrl().startsWith("http://127.0.0.1:"));
    assertFalse(server.getUrl().contains(json));

    HttpURLConnection connection = (HttpURLConnection) new URL(server.getUrl()).openConnection();
    connection.setConnectTimeout(2_000);
    connection.setReadTimeout(2_000);
    assertEquals(200, connection.getResponseCode());
    assertEquals("application/json; charset=utf-8", connection.getHeaderField("Content-Type"));
    assertEquals("no-store", connection.getHeaderField("Cache-Control"));
    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[256];
      int read;
      while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
      assertEquals(json, output.toString(StandardCharsets.UTF_8.name()));
    }
    for (int i = 0; i < 20 && !server.isClosed(); i++) Thread.sleep(25);
    assertTrue(server.isClosed());
  }

  @Test public void wrongPathDoesNotConsumePayload() throws Exception {
    OneShotJsonServer server = OneShotJsonServer.start("[]", 5_000);
    URL original = new URL(server.getUrl());
    HttpURLConnection wrong = (HttpURLConnection) new URL(
        "http://127.0.0.1:" + server.getPort() + "/wrong.json").openConnection();
    wrong.setConnectTimeout(2_000);
    wrong.setReadTimeout(2_000);
    assertEquals(404, wrong.getResponseCode());
    assertFalse(server.isClosed());

    HttpURLConnection correct = (HttpURLConnection) original.openConnection();
    correct.setConnectTimeout(2_000);
    correct.setReadTimeout(2_000);
    assertEquals(200, correct.getResponseCode());
    try (InputStream ignored = correct.getInputStream()) {
      while (ignored.read() >= 0) {}
    }
    server.close();
  }

  @Test public void closeRevokesEndpoint() throws Exception {
    OneShotJsonServer server = OneShotJsonServer.start("[]", 5_000);
    server.close();
    assertTrue(server.isClosed());
    try {
      new URL(server.getUrl()).openConnection().getInputStream();
      fail("closed loopback endpoint must reject connections");
    } catch (Exception expected) {
      assertTrue(server.isClosed());
    }
  }
}