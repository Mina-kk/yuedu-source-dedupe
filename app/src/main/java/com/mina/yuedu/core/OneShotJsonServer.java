package com.mina.yuedu.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A short-lived HTTP server bound only to Android's loopback interface.
 * It exposes one unguessable JSON URL and closes after the first successful GET.
 */
public final class OneShotJsonServer implements Closeable {
  private static final int MAX_HEADER_CHARS = 16 * 1024;
  private static final int CLIENT_TIMEOUT_MS = 10_000;

  private final ServerSocket serverSocket;
  private final String route;
  private final String url;
  private final long expiresAtMs;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private volatile byte[] payload;

  private OneShotJsonServer(String json, long ttlMs) throws IOException {
    if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("missing json");
    if (ttlMs <= 0) throw new IllegalArgumentException("ttl must be positive");
    payload = json.getBytes(StandardCharsets.UTF_8);
    route = "/import/" + UUID.randomUUID().toString().replace("-", "") + ".json";
    expiresAtMs = System.currentTimeMillis() + ttlMs;
    serverSocket = new ServerSocket();
    serverSocket.setReuseAddress(true);
    serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
    url = "http://127.0.0.1:" + serverSocket.getLocalPort() + route;
    Thread worker = new Thread(this::serve, "reader-import-loopback");
    worker.setDaemon(true);
    worker.start();
  }

  public static OneShotJsonServer start(String json, long ttlMs) throws IOException {
    return new OneShotJsonServer(json, ttlMs);
  }

  public String getUrl() {
    return url;
  }

  public int getPort() {
    return serverSocket.getLocalPort();
  }

  public boolean isClosed() {
    return closed.get();
  }

  private void serve() {
    try {
      while (!closed.get()) {
        long remaining = expiresAtMs - System.currentTimeMillis();
        if (remaining <= 0) break;
        serverSocket.setSoTimeout((int) Math.min(1000L, remaining));
        try (Socket client = serverSocket.accept()) {
          if (handle(client)) break;
        } catch (SocketTimeoutException ignored) {
          // Re-check expiry once per second.
        } catch (SocketException e) {
          if (!closed.get()) break;
        } catch (IOException ignored) {
          // A malformed/aborted client must not prevent the real reader request.
        }
      }
    } catch (SocketException ignored) {
      // Socket was closed while updating the accept timeout.
    } finally {
      close();
    }
  }

  /** @return true after the JSON was successfully served and the server should stop. */
  private boolean handle(Socket client) throws IOException {
    client.setSoTimeout(CLIENT_TIMEOUT_MS);
    BufferedReader reader = new BufferedReader(
        new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
    String requestLine = reader.readLine();
    int headerChars = requestLine == null ? 0 : requestLine.length();
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      headerChars += line.length();
      if (headerChars > MAX_HEADER_CHARS) {
        writeResponse(client, "431 Request Header Fields Too Large", null, false);
        return false;
      }
    }
    if (requestLine == null) return false;
    String[] parts = requestLine.split(" ");
    if (parts.length < 2) {
      writeResponse(client, "400 Bad Request", null, false);
      return false;
    }
    String method = parts[0];
    String target = parts[1];
    int query = target.indexOf('?');
    if (query >= 0) target = target.substring(0, query);
    if (!route.equals(target)) {
      writeResponse(client, "404 Not Found", null, false);
      return false;
    }
    if ("HEAD".equals(method)) {
      writeResponse(client, "200 OK", payload, false);
      return false;
    }
    if (!"GET".equals(method)) {
      writeResponse(client, "405 Method Not Allowed", null, false);
      return false;
    }
    byte[] body = payload;
    if (body == null) {
      writeResponse(client, "410 Gone", null, false);
      return false;
    }
    writeResponse(client, "200 OK", body, true);
    payload = null;
    return true;
  }

  private void writeResponse(Socket client, String status, byte[] body, boolean includeBody)
      throws IOException {
    int length = body == null ? 0 : body.length;
    BufferedWriter writer = new BufferedWriter(
        new OutputStreamWriter(client.getOutputStream(), StandardCharsets.US_ASCII));
    writer.write("HTTP/1.1 " + status + "\r\n");
    writer.write("Content-Type: application/json; charset=utf-8\r\n");
    writer.write("Content-Length: " + length + "\r\n");
    writer.write("Cache-Control: no-store\r\n");
    writer.write("Connection: close\r\n\r\n");
    writer.flush();
    if (includeBody && length > 0) {
      client.getOutputStream().write(body);
      client.getOutputStream().flush();
    }
  }

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    payload = null;
    try {
      serverSocket.close();
    } catch (IOException ignored) {}
  }
}
