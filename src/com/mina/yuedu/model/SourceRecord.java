package com.mina.yuedu.model;
import java.util.*;

public final class SourceRecord {
    private final int order;
    private final String name, url;
    private final Map<String, Object> raw;
    private final String nameOverride;

    public SourceRecord(int order, String name, String url, Map<String, Object> raw) {
        this(order, name, url, raw, null, true);
    }

    private SourceRecord(
            int order,
            String name,
            String url,
            Map<String, Object> raw,
            String nameOverride,
            boolean copyRaw
    ) {
        this.order = order;
        this.name = name;
        this.url = url;
        this.nameOverride = nameOverride;
        if (raw == null) this.raw = Collections.emptyMap();
        else if (copyRaw) this.raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
        else this.raw = Collections.unmodifiableMap(raw);
    }

    public static SourceRecord takeOwnership(int order, String name, String url, Map<String, Object> raw) {
        return new SourceRecord(order, name, url, raw, null, false);
    }

    public int getOrder() {
        return order;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    public String exportName() {
        return nameOverride != null ? nameOverride : name;
    }

    public SourceRecord withName(String n) {
        return new SourceRecord(order, n, url, raw, n, false);
    }

    public Map<String, Object> exportRaw() {
        if (nameOverride == null) return raw;
        Map<String, Object> map = new LinkedHashMap<>(raw);
        map.put("bookSourceName", nameOverride);
        return map;
    }
}
