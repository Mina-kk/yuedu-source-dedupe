package com.mina.yuedu.core;
import com.mina.yuedu.model.*;
import java.util.*;

public final class IncrementalDedupeEngine {
    public static final int DEFAULT_DETAIL_LIMIT = 200;

    private final DedupeMode mode;
    private final boolean cleanNames;
    private final int detailLimit;
    private final Map<String, SourceRecord> winners = new LinkedHashMap<>();
    private final List<DuplicateGroup> groups = new ArrayList<>();
    private final List<InvalidSource> invalid = new ArrayList<>();
    private int originalCount;
    private int duplicateCount;
    private int detailOverflow;
    private int invalidOverflow;
    private boolean released;

    public IncrementalDedupeEngine(DedupeMode mode, boolean cleanNames) {
        this(mode, cleanNames, DEFAULT_DETAIL_LIMIT);
    }

    public IncrementalDedupeEngine(DedupeMode mode, boolean cleanNames, int detailLimit) {
        this.mode = mode == null ? DedupeMode.STANDARD : mode;
        this.cleanNames = cleanNames;
        this.detailLimit = Math.max(0, detailLimit);
    }

    public void accept(SourceRecord source) {
        ensureOpen();
        originalCount++;
        String url = source.getUrl();
        if (url == null) {
            addInvalid(new InvalidSource(InvalidSource.Kind.MISSING_URL, source.getName()));
            return;
        }
        if (url.trim().isEmpty()) {
            addInvalid(new InvalidSource(InvalidSource.Kind.EMPTY_URL, source.getName()));
            return;
        }

        SourceRecord candidate = cleanNames ? source.withName(NameCleaner.clean(source.getName())) : source;
        try {
            String key = UrlNormalizer.key(url, mode);
            SourceRecord existing = winners.get(key);
            if (existing == null) {
                winners.put(key, candidate);
                return;
            }

            int candidateScore = score(candidate);
            int existingScore = score(existing);
            if (candidateScore > existingScore
                    || (candidateScore == existingScore && candidate.getOrder() < existing.getOrder())) {
                recordDuplicate(key, candidate, existing);
                winners.put(key, candidate);
            } else {
                recordDuplicate(key, existing, candidate);
            }
        } catch (Exception e) {
            addInvalid(new InvalidSource(InvalidSource.Kind.INVALID_URL, url));
        }
    }

    public void addInvalid(InvalidSource source) {
        ensureOpen();
        if (source == null) return;
        if (invalid.size() < detailLimit) invalid.add(source);
        else invalidOverflow++;
    }

    public void addInvalidAll(Collection<InvalidSource> sources) {
        if (sources == null) return;
        for (InvalidSource source : sources) addInvalid(source);
    }

    public DedupeResult finish() {
        ensureOpen();
        List<SourceRecord> retained = new ArrayList<>(winners.values());
        retained.sort(Comparator.comparingInt(SourceRecord::getOrder));
        return new DedupeResult(
                originalCount,
                retained,
                new ArrayList<>(groups),
                new ArrayList<>(invalid),
                duplicateCount,
                detailOverflow,
                invalidOverflow
        );
    }

    /** Drop internal maps/lists so GC can reclaim memory after result is taken. */
    public void release() {
        winners.clear();
        groups.clear();
        invalid.clear();
        originalCount = 0;
        duplicateCount = 0;
        detailOverflow = 0;
        invalidOverflow = 0;
        released = true;
    }

    public int getOriginalCount() {
        return originalCount;
    }

    private void ensureOpen() {
        if (released) throw new IllegalStateException("engine released");
    }

    private void recordDuplicate(String key, SourceRecord kept, SourceRecord removed) {
        duplicateCount++;
        if (groups.size() >= detailLimit) {
            detailOverflow++;
            return;
        }
        // Keep detail lightweight: only names, avoid retaining full raw of removed source in UI path.
        SourceRecord lightKept = kept.withName(kept.getName());
        SourceRecord lightRemoved = removed.withName(removed.getName());
        groups.add(new DuplicateGroup(key, reason(mode), lightKept, Collections.singletonList(lightRemoved)));
    }

    private static int score(SourceRecord s) {
        int n = 0;
        if (s.getName() != null && !s.getName().trim().isEmpty()) n += 10;
        if (s.getUrl() != null && !s.getUrl().trim().isEmpty()) n += 10;
        for (Object v : s.getRaw().values()) {
            if (v != null && !String.valueOf(v).trim().isEmpty()) n++;
        }
        Object en = s.getRaw().get("enabled");
        if (!(en instanceof Boolean) || (Boolean) en) n += 5;
        return n;
    }

    private static String reason(DedupeMode m) {
        return m == DedupeMode.AGGRESSIVE ? "激进模式下域名相同" : "规范化书源 URL 相同";
    }
}
