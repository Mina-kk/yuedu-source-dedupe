package com.mina.yuedu.network;

public final class YckPolicyTest {
    static void ok(boolean b, String m) {
        if (!b) throw new AssertionError(m);
    }

    static void eq(Object a, Object b, String m) {
        if (!java.util.Objects.equals(a, b)) throw new AssertionError(m);
    }

    public static void runAll() {
        ok(YckUrlPolicy.allowed("https://yckceo.vip/"), "vip");
        ok(YckUrlPolicy.allowed("https://www.yckceo.com/yuedu/"), "main");
        ok(YckUrlPolicy.allowed("https://www.yck2026.top/"), "backup");
        eq(YckSite.MAIN.entryUrl(), "https://www.yckceo.com/yuedu/shuyuans/index.html", "main entry");
        eq(YckSite.BACKUP.entryUrl(), "https://www.yck2026.top/", "backup entry");
        eq(YckSite.fromPreference("unknown"), YckSite.MAIN, "safe default");

        ok(YckUrlPolicy.json("https://www.yckceo.com/yuedu/shuyuan/json/id/1188.json"), "json");
        ok(YckUrlPolicy.json("https://www.yckceo.com/yuedu/shuyuans/json/id/1193.json"), "plural source collection URL");
        ok(!YckUrlPolicy.json("https://www.yckceo.com/help.html"), "not json");

        ok(YckUrlPolicy.safeResource("https://www.yckceo.com/static/js/int.js"), "trusted resource");
        ok(YckUrlPolicy.safeResource("https://unpkg.com/vue@3/dist/vue.global.js"), "allow unpkg cdn");
        ok(YckUrlPolicy.safeResource("https://cdn.jsdelivr.net/npm/jquery"), "allow jsdelivr");
        ok(YckUrlPolicy.safeResource("https://fonts.googleapis.com/css"), "allow google fonts");
        ok(!YckUrlPolicy.safeResource("https://fjlmcp.bajielm.com/okopymsuz6/3219/oy"), "block ad resource");
        ok(!YckUrlPolicy.safeResource("https://abe.ymmiyun.com/x.js"), "block ad cdn");
        ok(!YckUrlPolicy.safeResource("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"), "block adsense");

        ok(YckUrlPolicy.collectable("https://www.yckceo.com/d/ffb83f531771873a8"), "temporary URL");
        ok(!YckUrlPolicy.collectable("https://www.yckceo.com/d/"), "empty temporary token");
        ok(!YckUrlPolicy.collectable("https://evil.example/d/ffb83f"), "untrusted temporary host");

        String script = YckCollectorScript.source();
        ok(script.contains("data-yuedu-dedupe-button"), "marker");
        ok(script.contains("YckDedupe.addToDedupe"), "bridge");
        ok(script.contains("网络导入"), "locator");
        ok(script.contains("MutationObserver"), "dynamic popup observer");
        ok(script.contains("setTimeout"), "collector debounce");
        ok(script.contains("200"), "200ms debounce");

        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        eq(YckCollectionPolicy.collect("https://www.yckceo.com/yuedu/shuyuans/json/id/1193.json", urls), "added", "add");
        eq(YckCollectionPolicy.collect("https://www.yckceo.com/yuedu/shuyuans/json/id/1193.json", urls), "duplicate", "dedupe");
        eq(YckCollectionPolicy.collect("https://www.yckceo.com/help.html", urls), "invalid", "invalid");
    }
}
