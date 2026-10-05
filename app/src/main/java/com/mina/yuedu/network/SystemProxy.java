package com.mina.yuedu.network;

import android.content.Context;
import android.net.ConnectivityManager;
import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * 读取 Android 系统代理（WiFi/以太网手动代理、Clash 等"系统代理"模式），
 * 供下载与校验的 HTTP 请求使用——挂代理/梯子时网络请求才能走代理加速。
 * 全局 VPN 模式的流量由系统自动转发，无需（也不会命中）此代理。
 * （提取自 MIXUULS/yuedu-source-dedupe）
 */
public final class SystemProxy {
  private static volatile Proxy proxy;

  private SystemProxy() {}

  /** 在 Application/Activity 创建时调用一次。 */
  public static void init(Context context) {
    try {
      ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
      android.net.ProxyInfo pi = cm.getDefaultProxy();
      if (pi != null && pi.getHost() != null && !pi.getHost().isEmpty() && pi.getPort() > 0) {
        proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pi.getHost(), pi.getPort()));
      }
    } catch (Exception ignored) {
      // 读取失败时保持直连
    }
  }

  /** 系统代理，未设置时为 null（直连）。 */
  public static Proxy get() {
    return proxy;
  }
}
