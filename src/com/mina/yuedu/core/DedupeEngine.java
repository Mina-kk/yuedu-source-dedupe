package com.mina.yuedu.core;
import com.mina.yuedu.model.*;
import java.util.*;

public final class DedupeEngine {
    private DedupeEngine() {}

    public static DedupeResult run(List<SourceRecord> sources, DedupeMode mode, boolean clean) {
        IncrementalDedupeEngine engine = new IncrementalDedupeEngine(mode, clean);
        if (sources != null) {
            for (SourceRecord source : sources) engine.accept(source);
        }
        return engine.finish();
    }
}
