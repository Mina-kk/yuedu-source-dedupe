package com.mina.yuedu.ui;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import com.mina.yuedu.core.*;
import com.mina.yuedu.model.*;
import com.mina.yuedu.network.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class DedupeController implements DedupeView.Listener {
    public interface Host {
        void chooseFiles();
        void importReaderFromCache(File file);
        void saveJson(String name);
        ContentResolver contentResolver();
    }

    private enum State {IDLE, RUNNING, CANCELLING, PARTIAL_RESULT, COMPLETED, FAILED}

    private final Activity activity;
    private final DedupeView view;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yuedu-dedupe-worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final OperationMode operationMode = new OperationMode(DedupeMode.STANDARD);
    private final List<String> discovered = new ArrayList<>();
    private final List<InvalidSource> networkFailures = new ArrayList<>();

    private FetchManager manager;
    private IncrementalDedupeEngine engine;
    private DedupeResult result;
    private DedupeMode mode = DedupeMode.STANDARD;
    private boolean clean, partial, discard;
    private int concurrency = 2, nextOrder, localCount, networkCount, localFileCount;
    private State state = State.IDLE;
    private volatile boolean cancelRequested;

    public DedupeController(Activity a, DedupeView v, Host h) {
        activity = a;
        view = v;
        host = h;
        view.setListener(this);
    }

    public void importLocalUris(final List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        if (state == State.RUNNING || state == State.CANCELLING) {
            view.showError("当前任务进行中，请稍后再导入");
            return;
        }
        state = State.RUNNING;
        cancelRequested = false;
        operationMode.start();
        mode = operationMode.resultMode();
        clean = view.isCleanNames();
        rebuildEngineForNewRun(false);
        view.showLocalImportProgress(0, uris.size(), 0);

        worker.execute(() -> {
            int files = 0;
            int accepted = 0;
            try {
                for (Uri uri : uris) {
                    if (cancelRequested) break;
                    files++;
                    final int fileIndex = files;
                    final int totalFiles = uris.size();
                    try (InputStream in = host.contentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("无法打开文件");
                        try (Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65536)) {
                            int before = engine.getOriginalCount();
                            SourceParser.parseArrayStream(reader, nextOrder, record -> {
                                engine.accept(record);
                                nextOrder = Math.max(nextOrder, record.getOrder() + 1);
                            }, invalid -> engine.addInvalid(invalid));
                            int added = engine.getOriginalCount() - before;
                            if (added > 0) {
                                localCount += added;
                                localFileCount++;
                                accepted += added;
                            }
                        }
                    } catch (OutOfMemoryError oom) {
                        System.gc();
                        engine.addInvalid(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, "内存不足：" + safeName(uri)));
                    } catch (Exception e) {
                        engine.addInvalid(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, safeName(uri) + " · " + e.getMessage()));
                    }
                    final int acceptedNow = accepted;
                    main.post(() -> view.showLocalImportProgress(fileIndex, totalFiles, acceptedNow));
                }
                finishLocalImport(!cancelRequested);
            } catch (OutOfMemoryError oom) {
                System.gc();
                main.post(() -> {
                    state = State.FAILED;
                    view.showError("内存不足，请减少单次导入规模或分批处理");
                    view.showIdle();
                });
            } catch (Exception e) {
                main.post(() -> {
                    state = State.FAILED;
                    view.showError("本地导入失败：" + e.getMessage());
                    view.showIdle();
                });
            }
        });
    }

    private void finishLocalImport(boolean completed) {
        final DedupeResult done = takeResultAndReleaseEngine();
        final int files = localFileCount;
        final int local = localCount;
        main.post(() -> {
            result = done;
            state = completed ? State.COMPLETED : State.PARTIAL_RESULT;
            view.showLocalStatus(files, local);
            view.showResult(result, !completed, mode, localCount, networkCount);
        });
    }

    @Override
    public void onChooseFiles() {
        host.chooseFiles();
    }

    @Override
    public void onStart(List<String> urls, DedupeMode m, int c, boolean cl) {
        if (state == State.RUNNING || state == State.CANCELLING) return;
        operationMode.select(m);
        operationMode.start();
        mode = operationMode.resultMode();
        concurrency = Math.max(1, Math.min(5, c));
        clean = cl;
        partial = false;
        discard = false;
        cancelRequested = false;
        networkCount = 0;
        synchronized (networkFailures) {
            networkFailures.clear();
        }
        synchronized (discovered) {
            discovered.clear();
        }
        // Always rebuild engine for a new parse run so second pass does not retain first-run maps.
        rebuildEngineForNewRun(false);
        if (!ParseRequestDecision.shouldRun(localCount, urls.size())) {
            view.showError("请选择本地文件或输入网络地址");
            return;
        }
        if (urls.isEmpty()) {
            publishCurrentResult(false);
            return;
        }
        startFetch(urls, false);
    }

    private void startFetch(List<String> urls, boolean followUp) {
        state = State.RUNNING;
        manager = new FetchManager();
        manager.start(urls, concurrency, new FetchListener() {
            public void onProgress(FetchProgress p) {
                main.post(() -> view.showRunning(p));
            }

            public void onItem(String url, String body) {
                // Stream path is preferred; legacy body path is a fallback only.
                worker.execute(() -> consumeNetworkBody(url, body));
            }

            public void onFailure(String u, String m) {
                synchronized (networkFailures) {
                    networkFailures.add(new InvalidSource(InvalidSource.Kind.NETWORK_FAILURE, u + " · " + m));
                }
            }

            public void onFinished(boolean cancelled, boolean keep) {
                worker.execute(() -> {
                    if (discard) {
                        discard = false;
                        clearAllInternal(true);
                        main.post(() -> {
                            state = State.IDLE;
                            view.showCleared();
                        });
                        return;
                    }
                    List<String> more;
                    synchronized (discovered) {
                        more = new ArrayList<>(new LinkedHashSet<>(discovered));
                        discovered.clear();
                    }
                    if (!cancelled && !followUp && !more.isEmpty()) {
                        main.post(() -> startFetch(more, true));
                        return;
                    }
                    partial = cancelled && keep;
                    flushNetworkFailures();
                    publishCurrentResult(partial);
                });
            }
        }, (url, bodyStream) -> consumeNetworkStream(url, bodyStream));
    }

    private int consumeNetworkStream(String url, InputStream bodyStream) throws Exception {
        ensureEngine();
        final int[] added = {0};
        PushbackInputStream pin = new PushbackInputStream(bodyStream, 8192);
        int first = skipBomAndWs(pin);
        if (first < 0) throw new IOException("empty body");
        pin.unread(first);

        if (first == '[') {
            try (Reader reader = new BufferedReader(new InputStreamReader(pin, StandardCharsets.UTF_8), 65536)) {
                SourceParser.parseArrayStream(reader, nextOrder, record -> {
                    engine.accept(record);
                    nextOrder = Math.max(nextOrder, record.getOrder() + 1);
                    added[0]++;
                    networkCount++;
                }, invalid -> engine.addInvalid(invalid));
            }
            return added[0];
        }

        // Non-array: read a bounded snippet for YCK discovery / error reporting.
        String snippet = readLimitedUtf8(pin, 512 * 1024);
        List<String> found = SourceParser.discoverYckJsonUrls(snippet, url);
        if (!found.isEmpty()) {
            synchronized (discovered) {
                discovered.addAll(found);
            }
            return 0;
        }
        // Maybe JSON object wrapper with downloadUrl.
        try {
            Object parsed = MiniJson.parse(snippet);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                String indirect = SourceParser.extractIndirectUrl(map);
                if (indirect != null) {
                    synchronized (discovered) {
                        discovered.add(indirect);
                    }
                    return 0;
                }
            }
        } catch (Exception ignored) {
        }
        engine.addInvalid(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, url + " · not a JSON array"));
        return 0;
    }

    private void consumeNetworkBody(String url, String body) {
        try {
            ensureEngine();
            final int[] added = {0};
            try {
                SourceParser.parseArrayStream(body, nextOrder, record -> {
                    engine.accept(record);
                    nextOrder = Math.max(nextOrder, record.getOrder() + 1);
                    added[0]++;
                    networkCount++;
                }, invalid -> engine.addInvalid(invalid));
            } catch (Exception parseError) {
                List<String> found = SourceParser.discoverYckJsonUrls(body, url);
                if (found.isEmpty()) {
                    engine.addInvalid(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, url + " · " + parseError.getMessage()));
                } else {
                    synchronized (discovered) {
                        discovered.addAll(found);
                    }
                }
            }
        } catch (OutOfMemoryError oom) {
            System.gc();
            engine.addInvalid(new InvalidSource(InvalidSource.Kind.NETWORK_FAILURE, url + " · 内存不足"));
        }
    }

    private void flushNetworkFailures() {
        List<InvalidSource> failures;
        synchronized (networkFailures) {
            failures = new ArrayList<>(networkFailures);
            networkFailures.clear();
        }
        if (engine != null) engine.addInvalidAll(failures);
    }

    private void publishCurrentResult(boolean isPartial) {
        final DedupeResult done = takeResultAndReleaseEngine();
        main.post(() -> {
            result = done;
            state = isPartial ? State.PARTIAL_RESULT : State.COMPLETED;
            view.showLocalStatus(localFileCount, localCount);
            view.showResult(result, isPartial, mode, localCount, networkCount);
            // Encourage GC before a second large run.
            System.gc();
        });
    }

    @Override
    public void onClearAll() {
        if (state == State.RUNNING && manager != null) {
            discard = true;
            cancelRequested = true;
            manager.cancel(false);
        }
        worker.execute(() -> {
            clearAllInternal(true);
            main.post(() -> {
                state = State.IDLE;
                view.setInputText("");
                view.showLocalStatus(0, 0);
                view.showCleared();
            });
        });
    }

    private void clearAllInternal(boolean resetEngine) {
        synchronized (discovered) {
            discovered.clear();
        }
        synchronized (networkFailures) {
            networkFailures.clear();
        }
        localCount = 0;
        networkCount = 0;
        localFileCount = 0;
        nextOrder = 0;
        result = null;
        if (resetEngine) {
            if (engine != null) {
                try { engine.release(); } catch (Exception ignored) {}
            }
            engine = null;
        }
    }

    @Override
    public void onStop() {
        if (state != State.RUNNING) return;
        new AlertDialog.Builder(activity)
                .setTitle("停止解析？")
                .setItems(new String[]{"处理已加载数据", "全部放弃", "继续解析"}, (d, w) -> {
                    if (w == 0) {
                        state = State.CANCELLING;
                        partial = true;
                        cancelRequested = true;
                        if (manager != null) manager.cancel(true);
                    } else if (w == 1) {
                        state = State.CANCELLING;
                        discard = true;
                        cancelRequested = true;
                        if (manager != null) manager.cancel(false);
                    }
                }).show();
    }

    @Override
    public void onModeChanged(DedupeMode m, boolean cl) {
        operationMode.select(m);
        clean = cl;
    }

    public void exportToStream(OutputStream output) throws Exception {
        if (result == null) throw new IllegalStateException("没有可导出的数据");
        String stamp = new SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(new Date());
        String mark = "✔" + stamp + "检验去重（优质" + result.getRetained().size() + "）";
        Writer writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 65536);
        writer.write('[');
        boolean first = true;
        for (SourceRecord source : result.getRetained()) {
            if (!first) writer.write(',');
            first = false;
            Map<String, Object> map = new LinkedHashMap<>(source.exportRaw());
            map.put("bookSourceName", source.exportName());
            Object group = map.get("bookSourceGroup");
            String gs = group == null ? "" : String.valueOf(group);
            if (!gs.contains(mark)) map.put("bookSourceGroup", gs.isEmpty() ? mark : gs + "," + mark);
            MiniJson.writeValue(writer, map);
        }
        writer.write(']');
        writer.flush();
    }

    @Override
    public void onImport() {
        if (result == null || result.getRetained().isEmpty()) {
            view.showError("没有可导入的数据");
            return;
        }
        worker.execute(() -> {
            try {
                File file = new File(activity.getCacheDir(), "import.json");
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 65536)) {
                    exportToStream(out);
                }
                main.post(() -> host.importReaderFromCache(file));
            } catch (OutOfMemoryError oom) {
                System.gc();
                main.post(() -> view.showError("内存不足，导出失败"));
            } catch (Exception e) {
                main.post(() -> view.showError("导入���败：" + e.getMessage()));
            }
        });
    }

    @Override
    public void onSave() {
        if (result == null || result.getRetained().isEmpty()) {
            view.showError("没有可保存的数据");
            return;
        }
        String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
        host.saveJson("去重_" + result.getRetained().size() + "_" + d + ".json");
    }

    public void saveToUri(final Uri uri) {
        if (uri == null) return;
        worker.execute(() -> {
            try (OutputStream out = host.contentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("无法打开保存位置");
                try (BufferedOutputStream buffered = new BufferedOutputStream(out, 65536)) {
                    exportToStream(buffered);
                }
                main.post(() -> view.showError("已保存"));
            } catch (OutOfMemoryError oom) {
                System.gc();
                main.post(() -> view.showError("内存不足，保存失败"));
            } catch (Exception e) {
                main.post(() -> view.showError("保存失败：" + e.getMessage()));
            }
        });
    }

    private void rebuildEngineForNewRun(boolean clearLocalCounters) {
        List<SourceRecord> seed = null;
        if (!clearLocalCounters && result != null && localCount > 0 && !result.getRetained().isEmpty()) {
            // Preserve previously imported local sources into the new engine before network parse.
            seed = new ArrayList<>(result.getRetained());
        }
        if (result != null) {
            // Drop previous retained list so second run does not keep two full copies.
            result = null;
        }
        if (engine != null) {
            try { engine.release(); } catch (Exception ignored) {}
        }
        engine = new IncrementalDedupeEngine(mode, clean);
        if (clearLocalCounters) {
            localCount = 0;
            localFileCount = 0;
            nextOrder = 0;
            seed = null;
        }
        if (seed != null) {
            int maxOrder = nextOrder;
            for (SourceRecord record : seed) {
                engine.accept(record);
                maxOrder = Math.max(maxOrder, record.getOrder() + 1);
            }
            nextOrder = maxOrder;
        } else {
            nextOrder = Math.max(nextOrder, 0);
        }
        System.gc();
    }

    private DedupeResult takeResultAndReleaseEngine() {
        ensureEngine();
        DedupeResult done = engine.finish();
        try { engine.release(); } catch (Exception ignored) {}
        engine = null;
        return done;
    }

    private void ensureEngine() {
        if (engine == null) engine = new IncrementalDedupeEngine(mode, clean);
    }

    private static String safeName(Uri uri) {
        if (uri == null) return "unknown";
        String name = uri.getLastPathSegment();
        return name == null || name.trim().isEmpty() ? uri.toString() : name;
    }

    private static int skipBomAndWs(PushbackInputStream in) throws IOException {
        // UTF-8 BOM
        int b1 = in.read();
        if (b1 == 0xEF) {
            int b2 = in.read();
            int b3 = in.read();
            if (b2 != 0xBB || b3 != 0xBF) {
                if (b3 >= 0) in.unread(b3);
                if (b2 >= 0) in.unread(b2);
                if (b1 >= 0) in.unread(b1);
            } else {
                b1 = in.read();
            }
        }
        while (b1 >= 0 && (b1 == ' ' || b1 == '\n' || b1 == '\r' || b1 == '\t')) {
            b1 = in.read();
        }
        return b1;
    }

    private static String readLimitedUtf8(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) >= 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return out.toString("UTF-8");
    }

    public void restoreOptions(DedupeMode m, int c, boolean cl) {
        mode = m == null ? DedupeMode.STANDARD : m;
        operationMode.select(mode);
        concurrency = Math.max(1, Math.min(5, c));
        clean = cl;
    }

    public DedupeMode getMode() {
        return operationMode.selectedMode();
    }

    public int getConcurrency() {
        return concurrency;
    }

    public boolean isCleanNames() {
        return clean;
    }
}