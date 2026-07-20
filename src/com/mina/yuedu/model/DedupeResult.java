package com.mina.yuedu.model;
import java.util.*;

public final class DedupeResult {
    private final int originalCount;
    private final List<SourceRecord> retained;
    private final List<DuplicateGroup> groups;
    private final List<InvalidSource> invalid;
    private final int duplicateCount;
    private final int detailOverflow;
    private final int invalidOverflow;

    public DedupeResult(int n, List<SourceRecord> r, List<DuplicateGroup> g, List<InvalidSource> i) {
        this(n, r, g, i, -1, 0, 0);
    }

    public DedupeResult(
            int n,
            List<SourceRecord> r,
            List<DuplicateGroup> g,
            List<InvalidSource> i,
            int duplicateCount,
            int detailOverflow,
            int invalidOverflow
    ) {
        originalCount = n;
        retained = Collections.unmodifiableList(new ArrayList<>(r));
        groups = Collections.unmodifiableList(new ArrayList<>(g));
        invalid = Collections.unmodifiableList(new ArrayList<>(i));
        if (duplicateCount >= 0) this.duplicateCount = duplicateCount;
        else {
            int total = 0;
            for (DuplicateGroup group : groups) total += group.getRemoved().size();
            this.duplicateCount = total;
        }
        this.detailOverflow = Math.max(0, detailOverflow);
        this.invalidOverflow = Math.max(0, invalidOverflow);
    }

    public int getOriginalCount() {
        return originalCount;
    }

    public List<SourceRecord> getRetained() {
        return retained;
    }

    public List<DuplicateGroup> getDuplicateGroups() {
        return groups;
    }

    public List<InvalidSource> getInvalid() {
        return invalid;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public int getDetailOverflow() {
        return detailOverflow;
    }

    public int getInvalidOverflow() {
        return invalidOverflow;
    }

    public int getInvalidCount() {
        return invalid.size() + invalidOverflow;
    }
}
