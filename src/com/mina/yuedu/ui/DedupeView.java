package com.mina.yuedu.ui;
import android.content.*;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import com.mina.yuedu.model.*;
import com.mina.yuedu.network.FetchProgress;
import java.util.*;

public final class DedupeView extends ScrollView {
    public interface Listener {
        void onChooseFiles();
        void onStart(List<String> urls, DedupeMode mode, int concurrency, boolean clean);
        void onStop();
        void onModeChanged(DedupeMode mode, boolean clean);
        void onClearAll();
        void onImport();
        void onSave();
    }

    private Listener listener;
    private final LinearLayout root, resultBox, duplicateDetails, invalidDetails;
    private final EditText urls;
    private final ProgressBar progress;
    private Switch cleanSwitch;
    private TextView modeDescription;
    private final TextView progressText, statusText, statsText, dupHeader, invalidHeader, localStatus;
    private final java.util.List<View> taskLocked = new java.util.ArrayList<View>();
    private final Button start, stop, importBtn, saveBtn;
    private final View runningCard;
    private DedupeMode mode = DedupeMode.STANDARD;
    private int concurrency = 2, renderedGroups, renderedInvalid;
    private DedupeResult current;

    public DedupeView(Context c) {
        super(c);
        setFillViewport(true);
        setBackgroundColor(AppleStyles.BG);
        setHorizontalScrollBarEnabled(false);
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AppleStyles.dp(c, 16), AppleStyles.dp(c, 20), AppleStyles.dp(c, 16), AppleStyles.dp(c, 32));
        addView(root, new LayoutParams(-1, -2));

        TextView title = AppleStyles.text(c, "阅读书源去重", 24, AppleStyles.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, lp(-1, -2, 0, 0, 0, 18));

        LinearLayout input = AppleStyles.card(c);
        root.addView(input, lp(-1, -2, 0, 0, 0, 14));
        Button choose = AppleStyles.button(c, "选择 JSON 文件", false);
        input.addView(choose, new LinearLayout.LayoutParams(-1, AppleStyles.dp(c, 50)));
        choose.setOnClickListener(v -> {
            if (listener != null) listener.onChooseFiles();
        });
        TextView hint = AppleStyles.text(c, "支持本地 JSON 多选，也可每行粘贴一个网络地址", 13, AppleStyles.MUTED);
        input.addView(hint, lp(-1, -2, 0, 10, 0, 3));
        localStatus = AppleStyles.text(c, "", 13, AppleStyles.GREEN);
        localStatus.setVisibility(GONE);
        input.addView(localStatus, lp(-1, -2, 0, 0, 0, 8));
        urls = new EditText(c);
        urls.setHint("输入 JSON 或 YCK 地址，每行一个");
        urls.setTextSize(14);
        urls.setTextColor(AppleStyles.TEXT);
        urls.setHintTextColor(0xffaeaeb2);
        urls.setGravity(Gravity.TOP);
        urls.setMinLines(4);
        urls.setHorizontallyScrolling(false);
        urls.setSingleLine(false);
        urls.setBackground(AppleStyles.bordered(0xfffafafa, AppleStyles.LINE, c));
        urls.setPadding(AppleStyles.dp(c, 12), AppleStyles.dp(c, 10), AppleStyles.dp(c, 12), AppleStyles.dp(c, 10));
        input.addView(urls, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout modeRow = new LinearLayout(c);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView modeLabel = label("去重模式");
        modeRow.addView(modeLabel, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clearAll = AppleStyles.text(c, "清空", 14, AppleStyles.BLUE);
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setOnClickListener(v -> {
            if (listener != null) listener.onClearAll();
        });
        modeRow.addView(clearAll, new LinearLayout.LayoutParams(AppleStyles.dp(c, 52), -2));
        input.addView(modeRow, lp(-1, -2, 0, 14, 0, 6));
        input.addView(options(new String[]{"标准", "严格", "激进"}, 0, i -> {
            mode = DedupeMode.values()[i];
            updateModeDescription();
            if (listener != null) listener.onModeChanged(mode, cleanSwitch.isChecked());
        }), new LinearLayout.LayoutParams(-1, AppleStyles.dp(c, 42)));
        modeDescription = AppleStyles.text(c, mode.description(), 13, AppleStyles.MUTED);
        modeDescription.setPadding(AppleStyles.dp(c, 4), AppleStyles.dp(c, 7), AppleStyles.dp(c, 4), 0);
        input.addView(modeDescription);

        input.addView(label("并发请求"), lp(-1, -2, 0, 14, 0, 6));
        input.addView(options(new String[]{"1", "2", "3", "4", "5"}, 1, i -> concurrency = i + 1), new LinearLayout.LayoutParams(-1, AppleStyles.dp(c, 42)));

        cleanSwitch = new Switch(c);
        cleanSwitch.setText("清理名称中的装饰符号");
        cleanSwitch.setTextColor(AppleStyles.TEXT);
        cleanSwitch.setTextSize(14);
        input.addView(cleanSwitch, lp(-1, -2, 0, 12, 0, 8));
        taskLocked.add(cleanSwitch);
        cleanSwitch.setOnCheckedChangeListener((b, on) -> {
            if (listener != null) listener.onModeChanged(mode, on);
        });
        start = AppleStyles.button(c, "解析网络源", true);
        input.addView(start, new LinearLayout.LayoutParams(-1, AppleStyles.dp(c, 50)));
        start.setOnClickListener(v -> {
            if (listener != null) listener.onStart(getUrls(), mode, concurrency, cleanSwitch.isChecked());
        });

        runningCard = AppleStyles.card(c);
        root.addView(runningCard, lp(-1, -2, 0, 0, 0, 14));
        progressText = AppleStyles.text(c, "等待开始", 14, AppleStyles.TEXT);
        ((LinearLayout) runningCard).addView(progressText);
        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        ((LinearLayout) runningCard).addView(progress, lp(-1, AppleStyles.dp(c, 6), 0, 8, 0, 8));
        statusText = AppleStyles.text(c, "", 13, AppleStyles.MUTED);
        ((LinearLayout) runningCard).addView(statusText);
        stop = AppleStyles.button(c, "停止解析", false);
        stop.setTextColor(AppleStyles.RED);
        ((LinearLayout) runningCard).addView(stop, lp(-1, AppleStyles.dp(c, 46), 0, 10, 0, 0));
        stop.setOnClickListener(v -> {
            if (listener != null) listener.onStop();
        });
        runningCard.setVisibility(GONE);

        resultBox = AppleStyles.card(c);
        root.addView(resultBox, new LinearLayout.LayoutParams(-1, -2));
        resultBox.setVisibility(GONE);
        statsText = AppleStyles.text(c, "", 16, AppleStyles.TEXT);
        statsText.setGravity(Gravity.CENTER);
        resultBox.addView(statsText, lp(-1, -2, 0, 0, 0, 12));
        dupHeader = clickHeader("重复书源");
        resultBox.addView(dupHeader);
        duplicateDetails = new LinearLayout(c);
        duplicateDetails.setOrientation(LinearLayout.VERTICAL);
        duplicateDetails.setVisibility(GONE);
        resultBox.addView(duplicateDetails);
        invalidHeader = clickHeader("无效书源");
        resultBox.addView(invalidHeader, lp(-1, -2, 0, 10, 0, 0));
        invalidDetails = new LinearLayout(c);
        invalidDetails.setOrientation(LinearLayout.VERTICAL);
        invalidDetails.setVisibility(GONE);
        resultBox.addView(invalidDetails);
        importBtn = AppleStyles.button(c, "导入到阅读", true);
        resultBox.addView(importBtn, lp(-1, AppleStyles.dp(c, 50), 0, 16, 0, 8));
        saveBtn = AppleStyles.button(c, "保存 JSON", false);
        resultBox.addView(saveBtn, new LinearLayout.LayoutParams(-1, AppleStyles.dp(c, 50)));
        importBtn.setOnClickListener(v -> {
            if (listener != null) listener.onImport();
        });
        saveBtn.setOnClickListener(v -> {
            if (listener != null) listener.onSave();
        });
        dupHeader.setOnClickListener(v -> toggleDuplicates());
        invalidHeader.setOnClickListener(v -> toggleInvalid());
    }

    private void updateModeDescription() {
        modeDescription.setText(mode.description());
    }

    private TextView label(String s) {
        return AppleStyles.text(getContext(), s, 14, AppleStyles.MUTED);
    }

    private TextView clickHeader(String s) {
        TextView v = AppleStyles.text(getContext(), s, 15, AppleStyles.TEXT);
        v.setPadding(0, AppleStyles.dp(getContext(), 10), 0, AppleStyles.dp(getContext(), 10));
        return v;
    }

    private interface Choice {
        void pick(int i);
    }

    private LinearLayout options(String[] names, int selected, Choice cb) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(AppleStyles.round(0xffe9e9eb, 10, getContext()));
        final TextView[] a = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int x = i;
            a[i] = AppleStyles.text(getContext(), names[i], 14, AppleStyles.TEXT);
            a[i].setGravity(Gravity.CENTER);
            a[i].setBackground(AppleStyles.round(i == selected ? AppleStyles.CARD : 0x00ffffff, 9, getContext()));
            a[i].setOnClickListener(v -> {
                for (TextView q : a) q.setBackground(AppleStyles.round(0x00ffffff, 9, getContext()));
                v.setBackground(AppleStyles.round(AppleStyles.CARD, 9, getContext()));
                cb.pick(x);
            });
            taskLocked.add(a[i]);
            row.addView(a[i], new LinearLayout.LayoutParams(0, -1, 1));
        }
        return row;
    }

    private List<String> getUrls() {
        List<String> x = new ArrayList<>();
        for (String s : urls.getText().toString().split("\\r?\\n")) if (!s.trim().isEmpty()) x.add(s.trim());
        return x;
    }

    public String getInputText() {
        return urls.getText().toString();
    }

    public void setInputText(String s) {
        urls.setText(s);
    }

    public boolean appendUrlIfAbsent(String u) {
        String clean = u == null ? "" : u.trim();
        if (clean.isEmpty()) return false;
        String s = getInputText();
        for (String x : s.split("\\r?\\n")) if (x.trim().equals(clean)) return false;
        urls.setText(s.trim().isEmpty() ? clean : s + "\n" + clean);
        return true;
    }

    public void appendUrl(String u) {
        if (appendUrlIfAbsent(u)) {
            urls.requestFocus();
            post(() -> fullScroll(FOCUS_UP));
        }
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isCleanNames() {
        return cleanSwitch.isChecked();
    }

    public void showRunning(FetchProgress p) {
        runningCard.setVisibility(VISIBLE);
        resultBox.setVisibility(GONE);
        setTaskRunning(true);
        int total = Math.max(1, p.getTotal());
        progress.setProgress(p.getCompleted() * 100 / total);
        progressText.setText("正在解析书源    " + p.getCompleted() + " / " + p.getTotal());
        statusText.setText("成功 " + p.getSucceeded() + " · 失败 " + p.getFailed() + " · 已发现 " + p.getDiscoveredSources() + " 条");
    }

    public void showLocalImportProgress(int fileIndex, int totalFiles, int accepted) {
        runningCard.setVisibility(VISIBLE);
        resultBox.setVisibility(GONE);
        setTaskRunning(true);
        int total = Math.max(1, totalFiles);
        progress.setProgress(fileIndex * 100 / total);
        progressText.setText("正在导入本地文件    " + fileIndex + " / " + totalFiles);
        statusText.setText("已接受 " + accepted + " 条书源");
    }

    public void showIdle() {
        runningCard.setVisibility(GONE);
        setTaskRunning(false);
    }

    public void setTaskRunning(boolean running) {
        start.setEnabled(!running);
        for (View v : taskLocked) v.setEnabled(!running);
    }

    public void showError(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_LONG).show();
    }

    public void showCleared() {
        showIdle();
        resultBox.setVisibility(GONE);
        duplicateDetails.removeAllViews();
        invalidDetails.removeAllViews();
        current = null;
        Toast.makeText(getContext(), "已清空本地和网络书源", Toast.LENGTH_SHORT).show();
    }

    public void showLocalStatus(int files, int sources) {
        if (files <= 0) {
            localStatus.setVisibility(GONE);
            return;
        }
        localStatus.setText("已添加本地 JSON：" + files + " 个文件 · " + sources + " 条书源");
        localStatus.setVisibility(VISIBLE);
    }

    public void showResult(DedupeResult r, boolean partial, DedupeMode mode, int local, int network) {
        current = r;
        showIdle();
        resultBox.setVisibility(VISIBLE);
        statsText.setText(com.mina.yuedu.core.ResultSummary.format(mode, local, network, r, partial));
        dupHeader.setText("重复书源 " + r.getDuplicateCount() + " 条  ›");
        invalidHeader.setText("无效书源 " + r.getInvalidCount() + " 条  ›");
        duplicateDetails.removeAllViews();
        invalidDetails.removeAllViews();
        renderedGroups = 0;
        renderedInvalid = 0;
        duplicateDetails.setVisibility(GONE);
        invalidDetails.setVisibility(GONE);
    }

    private void toggleDuplicates() {
        if (current == null) return;
        if (duplicateDetails.getVisibility() == VISIBLE) {
            duplicateDetails.setVisibility(GONE);
            return;
        }
        duplicateDetails.setVisibility(VISIBLE);
        if (renderedGroups == 0) loadMoreGroups();
    }

    private void loadMoreGroups() {
        int end = Math.min(renderedGroups + 50, current.getDuplicateGroups().size());
        for (int i = renderedGroups; i < end; i++) {
            DuplicateGroup g = current.getDuplicateGroups().get(i);
            StringBuilder s = new StringBuilder("保留：").append(g.getKept().getName()).append("\n删除：");
            for (SourceRecord r : g.getRemoved()) s.append(r.getName()).append("；");
            s.append("\n原因：").append(g.getReason());
            TextView v = AppleStyles.text(getContext(), s.toString(), 13, AppleStyles.MUTED);
            v.setPadding(0, 8, 0, 8);
            duplicateDetails.addView(v);
        }
        renderedGroups = end;
        if (end < current.getDuplicateGroups().size()) {
            Button more = AppleStyles.button(getContext(), "加载更多", false);
            more.setOnClickListener(v -> {
                duplicateDetails.removeView(v);
                loadMoreGroups();
            });
            duplicateDetails.addView(more);
        } else if (current.getDetailOverflow() > 0) {
            TextView tip = AppleStyles.text(
                    getContext(),
                    "还有 " + current.getDetailOverflow() + " 条重复详情未加载到内存，仅展示前 " + current.getDuplicateGroups().size() + " 组",
                    12,
                    AppleStyles.MUTED
            );
            tip.setPadding(0, 8, 0, 4);
            duplicateDetails.addView(tip);
        }
    }

    private void toggleInvalid() {
        if (current == null) return;
        if (invalidDetails.getVisibility() == VISIBLE) {
            invalidDetails.setVisibility(GONE);
            return;
        }
        invalidDetails.setVisibility(VISIBLE);
        if (renderedInvalid == 0) loadMoreInvalid();
    }

    private void loadMoreInvalid() {
        int end = Math.min(renderedInvalid + 50, current.getInvalid().size());
        for (int i = renderedInvalid; i < end; i++) {
            InvalidSource x = current.getInvalid().get(i);
            TextView v = AppleStyles.text(getContext(), kindLabel(x.getKind()) + " · " + x.getDetail(), 13, AppleStyles.MUTED);
            v.setPadding(0, 6, 0, 6);
            invalidDetails.addView(v);
        }
        renderedInvalid = end;
        if (end < current.getInvalid().size()) {
            Button more = AppleStyles.button(getContext(), "加载更多", false);
            more.setOnClickListener(v -> {
                invalidDetails.removeView(v);
                loadMoreInvalid();
            });
            invalidDetails.addView(more);
        } else if (current.getInvalidOverflow() > 0) {
            TextView tip = AppleStyles.text(
                    getContext(),
                    "还有 " + current.getInvalidOverflow() + " 条错误详情未加载到内存",
                    12,
                    AppleStyles.MUTED
            );
            tip.setPadding(0, 8, 0, 4);
            invalidDetails.addView(tip);
        }
    }

    private String kindLabel(InvalidSource.Kind k) {
        switch (k) {
            case NOT_OBJECT: return "条目不是对象";
            case MISSING_URL: return "缺少 bookSourceUrl";
            case EMPTY_URL: return "URL 为空";
            case INVALID_URL: return "URL 格式错误";
            case NOT_JSON_ARRAY: return "文件不是 JSON 数组";
            case NETWORK_FAILURE: return "网络加载失败";
            case NO_YCK_JSON: return "YCK 页面未发现 JSON";
            default: return k.name();
        }
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}