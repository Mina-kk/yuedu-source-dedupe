package com.mina.yuedu.core;
import com.mina.yuedu.model.*;
import java.io.StringReader;
import java.util.*;

public final class CoreTest {
    private static void eq(Object a, Object b, String m) {
        if (!Objects.equals(a, b)) throw new AssertionError(m + ": " + a + " != " + b);
    }

    private static void ok(boolean v, String m) {
        if (!v) throw new AssertionError(m);
    }

    private static SourceRecord src(int o, String n, String u, int c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bookSourceName", n);
        r.put("bookSourceUrl", u);
        for (int i = 0; i < c; i++) r.put("rule" + i, "x");
        return SourceRecord.takeOwnership(o, n, u, r);
    }

    public static void runAll() throws Exception {
        eq(UrlNormalizer.key("HTTPS://Example.com:443/a#x", DedupeMode.STRICT), "https://example.com/a", "strict");
        eq(UrlNormalizer.key("https://example.com/a?utm_source=x&id=1", DedupeMode.STANDARD), "https://example.com/a?utm_source=x&id=1", "standard");
        eq(UrlNormalizer.key("https://www.example.com/a", DedupeMode.AGGRESSIVE), "example.com", "aggressive");

        List<SourceRecord> in = Arrays.asList(
                src(0, "旧版😀", "https://example.com/a", 1),
                src(1, "完整版", "https://example.com/a#fragment", 5),
                src(2, "另一路径", "https://example.com/b", 2),
                src(3, "坏链接🐧", "not a url", 2)
        );
        DedupeResult strict = DedupeEngine.run(in, DedupeMode.STRICT, false);
        eq(strict.getRetained().size(), 2, "strict retained");
        eq(strict.getDuplicateGroups().size(), 1, "groups");
        eq(strict.getDuplicateGroups().get(0).getKept().getName(), "完整版", "score");
        eq(strict.getInvalid().get(0).getKind(), InvalidSource.Kind.INVALID_URL, "invalid");
        eq(DedupeEngine.run(in, DedupeMode.AGGRESSIVE, false).getRetained().size(), 1, "aggressive retained");

        IncrementalDedupeEngine incremental = new IncrementalDedupeEngine(DedupeMode.STRICT, false, 1);
        for (SourceRecord source : in) incremental.accept(source);
        DedupeResult limited = incremental.finish();
        eq(limited.getDuplicateCount(), 1, "incremental duplicate count");
        eq(limited.getDuplicateGroups().size(), 1, "detail retained under limit");
        eq(limited.getDetailOverflow(), 0, "no overflow for single duplicate");

        IncrementalDedupeEngine overflowEngine = new IncrementalDedupeEngine(DedupeMode.STANDARD, false, 1);
        for (int i = 0; i < 5; i++) overflowEngine.accept(src(i, "n" + i, "https://dup.example/a", 1));
        DedupeResult overflow = overflowEngine.finish();
        eq(overflow.getDuplicateCount(), 4, "overflow duplicate count");
        eq(overflow.getDuplicateGroups().size(), 1, "overflow group cap");
        eq(overflow.getDetailOverflow(), 3, "overflow count");

        eq(NameCleaner.clean("  书源😀\u200B  "), "书源", "clean");
        ok(DedupeMode.STANDARD.description().contains("路径"), "standard description");

        String encoded = MiniJson.stringify("a\u0000b\u000bc\fd");
        for (int i = 0; i < encoded.length(); i++) ok(encoded.charAt(i) >= 0x20, "JSON control escaped");

        StringBuilder streamOut = new StringBuilder();
        MiniJson.writeValue(streamOut, Collections.singletonMap("k", "v"));
        eq(streamOut.toString(), "{\"k\":\"v\"}", "stream write");

        final int[] streamed = {0};
        MiniJson.parseArrayStream(
                new StringReader("[{\"bookSourceName\":\"A\",\"bookSourceUrl\":\"https://a.example/x\"},{\"bookSourceName\":\"B\",\"bookSourceUrl\":\"https://b.example/y\"}]"),
                object -> streamed[0]++
        );
        eq(streamed[0], 2, "stream parse count");

        SourceBuckets buckets = new SourceBuckets();
        buckets.addLocal(Arrays.asList(src(0, "local", "https://local.example/a", 1)));
        buckets.replaceNetwork(Arrays.asList(src(1, "network", "https://network.example/a", 1)));
        eq(buckets.all().size(), 2, "local and network merge");
        buckets.replaceNetwork(Arrays.asList(src(2, "network2", "https://network.example/b", 1)));
        eq(buckets.all().size(), 2, "new network does not clear local");
        eq(buckets.localCount(), 1, "local count");
        eq(buckets.networkCount(), 1, "network count");
        buckets.replaceNetwork(java.util.Collections.<SourceRecord>emptyList());
        eq(buckets.localCount(), 1, "network replacement retains local");
        eq(buckets.networkCount(), 0, "network clear only");

        DedupeResult sample = DedupeEngine.run(java.util.Arrays.asList(src(0, "a", "https://a.example/x", 1)), DedupeMode.AGGRESSIVE, false);
        String summary = ResultSummary.format(DedupeMode.AGGRESSIVE, 5, 7, sample, false);
        ok(summary.contains("当前去重模式：激进"), "active mode shown");
        ok(summary.contains("本地书源：5 · 网络书源：7"), "bucket counts shown");

        OperationMode op = new OperationMode(DedupeMode.STANDARD);
        op.select(DedupeMode.AGGRESSIVE);
        op.start();
        op.select(DedupeMode.STRICT);
        eq(op.resultMode(), DedupeMode.AGGRESSIVE, "running result mode frozen");
        eq(op.selectedMode(), DedupeMode.STRICT, "next mode changed only");

        eq(ParseRequestDecision.shouldRun(1, 0), true, "local-only parse runs");
        eq(ParseRequestDecision.shouldRun(0, 1), true, "network parse runs");
        eq(ParseRequestDecision.shouldRun(0, 0), false, "empty parse rejected");

        eq(UrlNormalizer.completeKey("HTTPS://Example.COM:443#x"), UrlNormalizer.completeKey("https://example.com/"), "complete URL equivalent");
        ok(!UrlNormalizer.completeKey("https://example.com/a").equals(UrlNormalizer.completeKey("https://example.com/b")), "path remains identity");

        List<SourceRecord> sameHostPaths = Arrays.asList(
                src(0, "one", "https://same.example/a", 1),
                src(1, "two", "https://same.example/b", 1)
        );
        eq(DedupeEngine.run(sameHostPaths, DedupeMode.STANDARD, false).getRetained().size(), 2, "standard retains same-host paths");
        eq(DedupeEngine.run(sameHostPaths, DedupeMode.STRICT, false).getRetained().size(), 2, "strict retains same-host paths");
        eq(DedupeEngine.run(sameHostPaths, DedupeMode.AGGRESSIVE, false).getRetained().size(), 1, "aggressive merges host");

        List<SourceRecord> sameUrl = Arrays.asList(
                src(0, "one", "https://same.example/a", 1),
                src(1, "two", "HTTPS://SAME.EXAMPLE:443/a#f", 1)
        );
        eq(DedupeEngine.run(sameUrl, DedupeMode.STANDARD, false).getDuplicateGroups().get(0).getReason(), "规范化书源 URL 相同", "reading identity reason");
        eq(UrlNormalizer.hostKey("https://example.com/a"), UrlNormalizer.hostKey("https://example.com/b"), "host key ignores path");

        // stress: 15k records with many duplicates
        IncrementalDedupeEngine stress = new IncrementalDedupeEngine(DedupeMode.STANDARD, false, 500);
        for (int i = 0; i < 15000; i++) {
            String url = "https://stress.example/" + (i % 3000);
            stress.accept(src(i, "s" + i, url, 2));
        }
        DedupeResult stressResult = stress.finish();
        eq(stressResult.getOriginalCount(), 15000, "stress original");
        eq(stressResult.getRetained().size(), 3000, "stress retained");
        eq(stressResult.getDuplicateCount(), 12000, "stress duplicates");
        eq(stressResult.getDuplicateGroups().size(), 500, "stress detail cap");
        eq(stressResult.getDetailOverflow(), 11500, "stress overflow");
    }
}
