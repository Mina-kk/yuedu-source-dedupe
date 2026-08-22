package com.mina.yuedu.network;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;

/** Parse-download policy: same-host pacing and which failures are worth a short retry. */
public final class FetchPolicy {
  public static final int MAX_ATTEMPTS = 3;
  public static final int MAX_PER_HOST = 2;
  public static final int CONNECT_TIMEOUT_MS = 5_000;
  public static final int READ_TIMEOUT_MS = 45_000;
  public static final String USER_AGENT =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36";

  private FetchPolicy() {}

  public static String hostKey(String raw) {
    if (raw == null) return "";
    try {
      URL u = new URL(raw.trim());
      String host = u.getHost();
      if (host == null || host.isEmpty()) return raw.trim();
      host = host.toLowerCase(Locale.ROOT);
      if (host.startsWith("www.")) host = host.substring(4);
      return host;
    } catch (MalformedURLException e) {
      return raw.trim();
    }
  }

  public static String origin(String raw) {
    if (raw == null) return null;
    try {
      URL u = new URL(raw.trim());
      if (u.getProtocol() == null || u.getHost() == null) return null;
      return u.getProtocol() + "://" + u.getHost() + "/";
    } catch (MalformedURLException e) {
      return null;
    }
  }

  public static long backoffMs(int failedAttempt) {
    if (failedAttempt <= 1) return 400L;
    return 1200L;
  }

  public static boolean isRetryable(Throwable e) {
    if (e == null) return false;
    if (e instanceof SocketTimeoutException) return true;
    if (e instanceof ConnectException) return true;
    if (e instanceof UnknownHostException) return false;
    if (e instanceof SocketException) return true;
    if (e instanceof InterruptedIOException) {
      String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
      return m.contains("timeout") || m.contains("timed out");
    }
    String m = e.getMessage();
    if (m == null) return false;
    String s = m.toLowerCase(Locale.ROOT);
    if (s.contains("empty body")) return true;
    if (s.contains("connection reset") || s.contains("broken pipe") || s.contains("unexpected end")) {
      return true;
    }
    return s.contains("http 429") || s.contains("http 408")
        || s.contains("http 502") || s.contains("http 503") || s.contains("http 504");
  }
}
