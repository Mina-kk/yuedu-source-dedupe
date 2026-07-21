package com.mina.yuedu.network;
import java.net.*;
import java.util.*;

public final class YckUrlPolicy {
    private YckUrlPolicy() {}

    public static boolean allowed(String raw) {
        try {
            String h = host(raw);
            return isYckHost(h);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resource policy for WebView subresource loading.
     * Default-allow (except known ad hosts) so YCK pages can load static CDNs.
     * Navigation/collection still restricted by allowed/json/collectable.
     */
    public static boolean safeResource(String raw) {
        try {
            String h = host(raw);
            if (h.isEmpty()) return false;
            if (isBlockedHost(h)) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean collectable(String raw) {
        try {
            URL u = new URL(raw);
            String p = u.getPath();
            return allowed(raw) && (p.toLowerCase(Locale.ROOT).endsWith(".json") || p.matches("^/d/[^/?#]+$"));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean json(String raw) {
        try {
            return allowed(raw) && new URL(raw).getPath().toLowerCase(Locale.ROOT).endsWith(".json");
        } catch (Exception e) {
            return false;
        }
    }

    private static String host(String raw) throws Exception {
        return new URL(raw).getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isYckHost(String h) {
        return h.equals("yckceo.vip") || h.endsWith(".yckceo.vip")
                || h.equals("yckceo.com") || h.endsWith(".yckceo.com")
                || h.equals("yck2026.top") || h.endsWith(".yck2026.top")
                || h.equals("www.yckceo.vip");
    }

    private static boolean isBlockedHost(String h) {
        // Known ad / tracking hosts observed on YCK pages.
        if (h.equals("fjlmcp.bajielm.com") || h.endsWith(".bajielm.com")) return true;
        if (h.equals("abe.ymmiyun.com") || h.endsWith(".ymmiyun.com")) return true;
        if (h.equals("haokawx.lot-ml.com") || h.endsWith(".lot-ml.com")) return true;
        if (h.contains("doubleclick") || h.contains("googlesyndication")) return true;
        return false;
    }
}
