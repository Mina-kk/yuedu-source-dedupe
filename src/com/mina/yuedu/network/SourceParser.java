package com.mina.yuedu.network;
import com.mina.yuedu.core.MiniJson;
import com.mina.yuedu.model.*;
import java.io.Reader;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceParser {
    public static final class ParseResult {
        private final List<SourceRecord> records;
        private final List<InvalidSource> invalid;

        ParseResult(List<SourceRecord> r, List<InvalidSource> i) {
            records = r;
            invalid = i;
        }

        public List<SourceRecord> getRecords() {
            return records;
        }

        public List<InvalidSource> getInvalid() {
            return invalid;
        }
    }

    public interface RecordConsumer {
        void accept(SourceRecord record) throws Exception;
    }

    public interface InvalidConsumer {
        void accept(InvalidSource invalid);
    }

    private SourceParser() {}

    public static ParseResult parseArray(String json, int start) {
        final List<SourceRecord> out = new ArrayList<>();
        final List<InvalidSource> bad = new ArrayList<>();
        try {
            parseArrayStream(json, start, out::add, bad::add);
        } catch (Exception e) {
            bad.add(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, e.getMessage()));
        }
        return new ParseResult(out, bad);
    }

    public static int parseArrayStream(
            String json,
            int startOrder,
            RecordConsumer records,
            InvalidConsumer invalid
    ) throws Exception {
        AtomicInteger next = new AtomicInteger(startOrder);
        try {
            MiniJson.parseArrayStream(json, object -> {
                SourceRecord record = toRecord(object, next.getAndIncrement(), invalid);
                if (record != null) records.accept(record);
            });
        } catch (Exception e) {
            invalid.accept(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, e.getMessage()));
            throw e;
        }
        return next.get() - startOrder;
    }

    public static int parseArrayStream(
            Reader reader,
            int startOrder,
            RecordConsumer records,
            InvalidConsumer invalid
    ) throws Exception {
        AtomicInteger next = new AtomicInteger(startOrder);
        try {
            MiniJson.parseArrayStream(reader, object -> {
                SourceRecord record = toRecord(object, next.getAndIncrement(), invalid);
                if (record != null) records.accept(record);
            });
        } catch (Exception e) {
            invalid.accept(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, e.getMessage()));
            throw e;
        }
        return next.get() - startOrder;
    }

    private static SourceRecord toRecord(Map<String, Object> object, int order, InvalidConsumer invalid) {
        if (object == null) {
            invalid.accept(new InvalidSource(InvalidSource.Kind.NOT_OBJECT, "index " + order));
            return null;
        }
        Object name = object.get("bookSourceName");
        Object url = object.get("bookSourceUrl");
        LinkedHashMap<String, Object> owned = object instanceof LinkedHashMap
                ? (LinkedHashMap<String, Object>) object
                : new LinkedHashMap<>(object);
        return SourceRecord.takeOwnership(
                order,
                name == null ? "" : String.valueOf(name),
                url == null ? null : String.valueOf(url),
                owned
        );
    }

    public static String extractIndirectUrl(Map<String, Object> m) {
        for (String k : new String[]{"msg", "downloadUrl", "url"}) {
            Object v = m.get(k);
            if (v != null && String.valueOf(v).matches("https?://.+")) return String.valueOf(v);
        }
        return null;
    }

    public static List<String> discoverYckJsonUrls(String html, String base) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        Pattern p = Pattern.compile(
                "(?:href|value|data-url)\\s*=\\s*['\"]([^'\"]+?\\.json(?:\\?[^'\"]*)?)['\"]",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(html.replace("&amp;", "&"));
        while (m.find()) add(set, m.group(1), base);
        Pattern raw = Pattern.compile(
                "https?://[^\\s'\"<>]+/yuedu/(?:shuyuan|shuyuans|rss|rsss)/json/[^\\s'\"<>]+",
                Pattern.CASE_INSENSITIVE
        );
        m = raw.matcher(html);
        while (m.find()) add(set, m.group(), base);
        return new ArrayList<>(set);
    }

    private static void add(Set<String> s, String raw, String base) {
        try {
            URL u = new URL(new URL(base), raw);
            String p = u.getPath();
            if (p.matches("(?i).*/yuedu/(shuyuan|shuyuans|rss|rsss)/json/.*\\.json")) s.add(u.toString());
        } catch (Exception ignored) {
        }
    }
}
