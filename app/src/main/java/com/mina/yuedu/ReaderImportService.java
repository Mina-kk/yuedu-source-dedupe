package com.mina.yuedu;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import com.mina.yuedu.core.OneShotJsonServer;
import java.io.IOException;

/** Keeps the loopback JSON endpoint alive while Reading opens its native selection dialog. */
public final class ReaderImportService extends Service {
  private static final Object LOCK = new Object();
  private static OneShotJsonServer activeServer;

  /**
   * Starts a one-use loopback endpoint and a short-lived started service in the same process.
   * No source JSON is placed in an Intent or sent to a remote server.
   */
  public static String prepare(Context context, String json, long ttlMs) throws IOException {
    Context app = context.getApplicationContext();
    OneShotJsonServer candidate = OneShotJsonServer.start(json, ttlMs);
    synchronized (LOCK) {
      if (activeServer != null) activeServer.close();
      activeServer = candidate;
    }
    try {
      app.startService(new Intent(app, ReaderImportService.class));
    } catch (RuntimeException e) {
      synchronized (LOCK) {
        if (activeServer == candidate) activeServer = null;
      }
      candidate.close();
      throw e;
    }
    synchronized (LOCK) {
      if (activeServer != candidate || candidate.isClosed()) {
        app.stopService(new Intent(app, ReaderImportService.class));
        throw new IOException("loopback import endpoint stopped before launch");
      }
    }
    Thread monitor = new Thread(() -> {
      while (!candidate.isClosed()) {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      boolean wasActive;
      synchronized (LOCK) {
        wasActive = activeServer == candidate;
        if (wasActive) activeServer = null;
      }
      if (wasActive) app.stopService(new Intent(app, ReaderImportService.class));
    }, "reader-import-service-monitor");
    monitor.setDaemon(true);
    monitor.start();
    return candidate.getUrl();
  }

  public static void cancel(Context context) {
    closeActive();
    Context app = context.getApplicationContext();
    app.stopService(new Intent(app, ReaderImportService.class));
  }

  private static void closeActive() {
    synchronized (LOCK) {
      if (activeServer != null) activeServer.close();
      activeServer = null;
    }
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    return START_NOT_STICKY;
  }

  @Nullable @Override public IBinder onBind(Intent intent) {
    return null;
  }

  @Override public void onDestroy() {
    closeActive();
    super.onDestroy();
  }
}