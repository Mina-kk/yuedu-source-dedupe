package com.mina.yuedu.core;
import com.mina.yuedu.model.*;

public final class ResultSummary {
    private ResultSummary() {}

    public static String format(DedupeMode mode, int local, int network, DedupeResult r, boolean partial) {
        StringBuilder b = new StringBuilder();
        if (partial) b.append("结果不完整\n");
        b.append("当前去重模式：").append(mode.label()).append('\n');
        b.append("本地书源：").append(local).append(" · 网络书源：").append(network).append('\n');
        b.append("原始 ").append(r.getOriginalCount())
                .append("   重复 ").append(r.getDuplicateCount())
                .append("   有效 ").append(r.getRetained().size())
                .append("   错误 ").append(r.getInvalidCount());
        if (r.getDetailOverflow() > 0 || r.getInvalidOverflow() > 0) {
            b.append("\n详情仅保留前 ").append(IncrementalDedupeEngine.DEFAULT_DETAIL_LIMIT).append(" 条");
        }
        return b.toString();
    }
}
