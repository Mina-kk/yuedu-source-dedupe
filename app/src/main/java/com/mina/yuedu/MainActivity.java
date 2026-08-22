package com.mina.yuedu;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.provider.OpenableColumns;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import androidx.core.content.ContextCompat;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.mina.yuedu.check.*;
import com.mina.yuedu.core.*;
import com.mina.yuedu.model.*;
import com.mina.yuedu.network.*;
import com.mina.yuedu.ui.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
  private View dedupePage;
  private WebView yck;
  private TextView yckSiteButton, yckRefreshButton, backgroundTaskChip;
  private FrameLayout pageContainer;
  private TabLayout tabs;
  private TextInputEditText etUrls;
  private TextView tvLocalStatus, tvModeDesc, tvElapsed, tvProgress, tvStatus, tvStats, tvCheckElapsed, tvCheckStats;
  private volatile String urlBoxSnapshot = "";
  private View cardRunning, cardResult;
  private LinearLayout boxKindExport, rowCheckTiles;
  private TextView tvExportPreview, tvFilterHints;
  private final Map<SourceKind, KindRow> kindRows = new EnumMap<>(SourceKind.class);
  private LinearProgressIndicator progressBar;
  private MaterialSwitch switchCleanNames, switchOnlyUsable, switchCleanLogin;
  private MaterialButtonToggleGroup modeGroup;
  private Slider sliderConcurrency;
  private TextView tvFetchConcurrencyLabel;
  private MaterialButton btnParse, btnStop, btnCheck, btnImport, btnSave;

  private final SourceBuckets buckets = new SourceBuckets();
  private final UrlInbox urlInbox = new UrlInbox();
  private final List<InvalidSource> extraInvalid = new ArrayList<>();
  private final List<String> discovered = new ArrayList<>();
  private final AtomicInteger nextOrderSeq = new AtomicInteger();
  private final Handler elapsedHandler = new Handler(Looper.getMainLooper());
  private final ElapsedClock checkClock = new ElapsedClock();
  private final Runnable elapsedTick = new Runnable() {
    @Override public void run() {
      refreshElapsedView();
      flushCheckUi(false);
      if (checkClock.isRunning()) elapsedHandler.postDelayed(this, 250);
    }
  };
  private final OperationMode operationMode = new OperationMode(DedupeMode.STANDARD);
  private DedupeMode mode = DedupeMode.STANDARD;
  private DedupeResult result;
  private List<CheckSourceResult> checkResults = new ArrayList<>();
  private FastestUsableDedupe.Outcome fastestOutcome;
  private FetchManager fetchManager;
  private CheckSourceEngine checkEngine;
  private boolean cleanNames, partial, discard, onlyUsable, cleanLogin = true, deleteCaptcha = true;
  private boolean fetchRunning, checkRunning, checkCancelRequested;
  private int concurrency = 4, localFileCount, currentTab;
  private long lastCheckElapsedMs;
  private volatile CheckSourceSettings uiCheckSettings;
  private volatile int uiCheckDone, uiCheckTotal, uiCheckInFlight, uiCheckRound, uiCheckRounds;
  private int uiCheckProgressPct = -1;
  private volatile String uiCheckExtra = "";
  private volatile boolean uiCheckDirty;
  private final CheckSourceSettings checkSettings = new CheckSourceSettings();
  private String pendingSave, backgroundTaskText = "";
  private YckSite yckSite = YckSite.MAIN;
  private YckWebClient yckClient;
  private boolean yckLoaded, yckAutoFellBack;

  private ActivityResultLauncher<String[]> openDocs;
  private ActivityResultLauncher<String> createDoc;

  @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    setContentView(R.layout.activity_main);
    MaterialToolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    tabs = findViewById(R.id.tabs);
    pageContainer = findViewById(R.id.pageContainer);
    applySystemBarInsets(findViewById(R.id.rootCoordinator), findViewById(R.id.appBar), pageContainer);
    tabs.addTab(tabs.newTab().setText(R.string.tab_dedupe));
    tabs.addTab(tabs.newTab().setText(R.string.tab_yck));

    yckSite = YckSite.fromPreference(getSharedPreferences("yck", MODE_PRIVATE).getString("site", "main"));
    loadCheckSettings();

    dedupePage = getLayoutInflater().inflate(R.layout.page_dedupe, pageContainer, false);
    pageContainer.addView(dedupePage);
    bindDedupe(dedupePage);

    yck = new WebView(this);
    yck.setVisibility(View.GONE);
    pageContainer.addView(yck, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    setupYck();
    addYckSiteButton();
    addYckRefreshButton();
    addBackgroundTaskChip();

    tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
      @Override public void onTabSelected(TabLayout.Tab tab) {
        if (tab.getPosition() == 0) showDedupe(); else showYck();
      }
      @Override public void onTabUnselected(TabLayout.Tab tab) {}
      @Override public void onTabReselected(TabLayout.Tab tab) {}
    });

    openDocs = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
      if (uris == null) return;
      for (Uri u : uris) readChosen(u);
    });
    createDoc = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
      if (uri == null || pendingSave == null) return;
      try (OutputStream o = getContentResolver().openOutputStream(uri)) {
        o.write(pendingSave.getBytes(StandardCharsets.UTF_8));
        toast("已保存");
      } catch (Exception e) { toast("保存失败：" + e.getMessage()); }
    });

    updateModeDesc();
  }

  private void applySystemBarInsets(View root, View appBar, View content) {
    final int appLeft = appBar.getPaddingLeft();
    final int appTop = appBar.getPaddingTop();
    final int appRight = appBar.getPaddingRight();
    final int appBottom = appBar.getPaddingBottom();
    final int contentLeft = content.getPaddingLeft();
    final int contentTop = content.getPaddingTop();
    final int contentRight = content.getPaddingRight();
    final int contentBottom = content.getPaddingBottom();
    ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
      Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
      appBar.setPadding(appLeft, appTop + bars.top, appRight, appBottom);
      content.setPadding(contentLeft, contentTop, contentRight, contentBottom + bars.bottom);
      return windowInsets;
    });
    ViewCompat.requestApplyInsets(root);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void bindDedupe(View root) {
    etUrls = root.findViewById(R.id.etUrls);
    etUrls.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(Editable s) {
        urlBoxSnapshot = s == null ? "" : s.toString();
      }
    });
    tvLocalStatus = root.findViewById(R.id.tvLocalStatus);
    tvModeDesc = root.findViewById(R.id.tvModeDesc);
    tvElapsed = root.findViewById(R.id.tvElapsed);
    tvProgress = root.findViewById(R.id.tvProgress);
    tvStatus = root.findViewById(R.id.tvStatus);
    tvStats = root.findViewById(R.id.tvStats);
    tvCheckElapsed = root.findViewById(R.id.tvCheckElapsed);
    tvCheckStats = root.findViewById(R.id.tvCheckStats);
    boxKindExport = root.findViewById(R.id.boxKindExport);
    rowCheckTiles = root.findViewById(R.id.rowCheckTiles);
    tvExportPreview = root.findViewById(R.id.tvExportPreview);
    tvFilterHints = root.findViewById(R.id.tvFilterHints);
    kindRows.clear();
    bindKindRow(root, R.id.rowKindNovel, SourceKind.NOVEL, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindComic, SourceKind.COMIC, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindVideo, SourceKind.VIDEO, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindAudio, SourceKind.AUDIO, R.color.md_theme_primary);
    bindKindRow(root, R.id.rowKindFile, SourceKind.FILE, R.color.md_theme_primary);
    bindStatTile(root.findViewById(R.id.tileOriginal), "原始", R.color.md_theme_on_surface);
    bindStatTile(root.findViewById(R.id.tileDup), "重复", R.color.md_theme_secondary);
    bindStatTile(root.findViewById(R.id.tileKept), "有效", R.color.md_theme_success);
    bindStatTile(root.findViewById(R.id.tileBad), "错误", R.color.md_theme_error);
    bindStatTile(root.findViewById(R.id.tileOk), "可用", R.color.md_theme_success);
    bindStatTile(root.findViewById(R.id.tileFail), "失败", R.color.md_theme_error);
    bindStatTile(root.findViewById(R.id.tileTimeout), "超时", R.color.md_theme_secondary);
    cardRunning = root.findViewById(R.id.cardRunning);
    cardResult = root.findViewById(R.id.cardResult);
    progressBar = root.findViewById(R.id.progressBar);
    switchCleanNames = root.findViewById(R.id.switchCleanNames);
    switchOnlyUsable = root.findViewById(R.id.switchOnlyUsable);
    switchCleanLogin = root.findViewById(R.id.switchCleanLogin);
    modeGroup = root.findViewById(R.id.modeGroup);
    sliderConcurrency = root.findViewById(R.id.sliderConcurrency);
    tvFetchConcurrencyLabel = root.findViewById(R.id.tvFetchConcurrencyLabel);
    btnParse = root.findViewById(R.id.btnParse);
    btnStop = root.findViewById(R.id.btnStop);
    btnCheck = root.findViewById(R.id.btnCheck);
    btnImport = root.findViewById(R.id.btnImport);
    btnSave = root.findViewById(R.id.btnSave);

    root.findViewById(R.id.btnChooseJson).setOnClickListener(v -> openDocs.launch(new String[]{"application/json", "text/*", "*/*"}));
    root.findViewById(R.id.btnClear).setOnClickListener(v -> clearAll());
    modeGroup.addOnButtonCheckedListener((g, id, checked) -> {
      if (!checked) return;
      if (id == R.id.modeStandard) mode = DedupeMode.STANDARD;
      else if (id == R.id.modeStrict) mode = DedupeMode.STRICT;
      else mode = DedupeMode.AGGRESSIVE;
      operationMode.select(mode);
      updateModeDesc();
      if (result != null) recompute(partial);
    });
    sliderConcurrency.setValueFrom(1);
    sliderConcurrency.setValueTo(FetchManager.MAX_CONCURRENCY);
    float fetchConc = Math.max(1, Math.min(FetchManager.MAX_CONCURRENCY, concurrency));
    sliderConcurrency.setValue(fetchConc);
    updateFetchConcurrencyLabel(Math.round(fetchConc));
    sliderConcurrency.addOnChangeListener((s, value, fromUser) -> {
      concurrency = Math.round(value);
      updateFetchConcurrencyLabel(concurrency);
    });
    switchCleanNames.setOnCheckedChangeListener((b, on) -> { cleanNames = on; if (result != null) recompute(partial); });
    switchOnlyUsable.setOnCheckedChangeListener((b, on) -> { onlyUsable = on; refreshExportPreview(); });
    cleanLogin = checkPrefs().getBoolean("cleanLogin", true);
    if (switchCleanLogin != null) {
      switchCleanLogin.setChecked(cleanLogin);
      switchCleanLogin.setOnCheckedChangeListener((b, on) -> {
        cleanLogin = on;
        checkPrefs().edit().putBoolean("cleanLogin", on).apply();
        refreshExportPreview();
      });
    }
    btnParse.setOnClickListener(v -> startParse());
    btnStop.setOnClickListener(v -> stopCurrentTask());
    btnCheck.setOnClickListener(v -> showCheckDialog());
    btnImport.setOnClickListener(v -> importReader());
    btnSave.setOnClickListener(v -> saveJson());
  }

  private void updateModeDesc() { tvModeDesc.setText(mode.description()); }

  private void updateFetchConcurrencyLabel(int value) {
    if (tvFetchConcurrencyLabel == null) return;
    tvFetchConcurrencyLabel.setText("并发请求：" + value + "（解析下载，最高 " + FetchManager.MAX_CONCURRENCY + "）");
  }

  static String formatElapsed(long ms) {
    return ElapsedFormat.format(ms);
  }

  private List<String> getUrls() {
    CharSequence cs = etUrls == null ? "" : etUrls.getText();
    return urlInbox.merge(cs);
  }

  private void flushUrlsToBox() {
    if (etUrls == null) return;
    List<String> urls = urlInbox.merge(etUrls.getText());
    String joined = UrlInbox.join(urls);
    CharSequence cur = etUrls.getText();
    if (cur != null && joined.contentEquals(cur)) return;
    etUrls.setText(joined);
  }

  private void startParse() {
    operationMode.select(mode); operationMode.start(); mode = operationMode.resultMode();
    cleanNames = switchCleanNames.isChecked();
    concurrency = Math.round(sliderConcurrency.getValue());
    partial = false; discard = false; checkResults.clear(); fastestOutcome = null;
    stopElapsedTicker(false);
    synchronized (buckets) { buckets.replaceNetwork(Collections.emptyList()); }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    List<String> urls = urlInbox.takeAll(etUrls == null ? "" : etUrls.getText());
    if (etUrls != null) etUrls.setText(UrlInbox.join(urls));
    if (!ParseRequestDecision.shouldRun(buckets.localCount(), urls.size())) { toast("请选择本地文件或输入网络地址"); return; }
    if (urls.isEmpty()) { recompute(false); return; }
    startFetch(urls, false);
  }

  private void startFetch(List<String> urls, boolean followUp) {
    fetchRunning = true;
    checkRunning = false;
    uiCheckProgressPct = -1;
    btnStop.setEnabled(true);
    btnStop.setText("停止解析");
    updateBackgroundTask("后台解析 0 / " + urls.size());
    setTaskRunning(true);
    cardRunning.setVisibility(View.VISIBLE);
    cardResult.setVisibility(View.GONE);
    fetchManager = new FetchManager();
    fetchManager.start(urls, concurrency, new FetchListener() {
      @Override public void onProgress(FetchProgress p) {
        runOnUiThread(() -> {
          int total = Math.max(1, p.getTotal());
          int pct = p.getCompleted() * 100 / total;
          if (pct != uiCheckProgressPct) {
            uiCheckProgressPct = pct;
            progressBar.setProgressCompat(pct, false);
          }
          setTextIfChanged(tvProgress, "正在解析书源 " + p.getCompleted() + " / " + p.getTotal());
          String status = "成功 " + p.getSucceeded() + " · 失败 " + p.getFailed() + " · 已发现 " + p.getDiscoveredSources() + " 条";
          if (p.getRetrying() > 0) status += " · 慢网重试中";
          setTextIfChanged(tvStatus, status);
          updateBackgroundTask("后台解析 " + p.getCompleted() + " / " + p.getTotal());
        });
      }
      @Override public void onItem(String url, String body) {
        int start = nextOrderSeq.getAndAdd(Math.max(1, estimateRecordSlots(body)));
        SourceParser.ParseResult p = SourceParser.parseArray(body, start);
        synchronized (buckets) {
          if (!p.getRecords().isEmpty()) {
            buckets.addNetwork(p.getRecords());
          } else {
            String indirect = SourceParser.extractIndirectUrl(body);
            List<String> found = new ArrayList<>();
            if (indirect != null && !indirect.isEmpty()) found.add(indirect);
            found.addAll(SourceParser.discoverYckJsonUrls(body, url));
            if (found.isEmpty()) {
              synchronized (extraInvalid) {
                if (!p.getInvalid().isEmpty()) extraInvalid.addAll(p.getInvalid());
                else extraInvalid.add(new InvalidSource(InvalidSource.Kind.NOT_JSON_ARRAY, url + " · 响应非书源数组"));
              }
            } else synchronized (discovered) { discovered.addAll(found); }
          }
        }
      }
      @Override public void onFailure(String u, String m) {
        synchronized (extraInvalid) { extraInvalid.add(new InvalidSource(InvalidSource.Kind.NETWORK_FAILURE, u + " · " + m)); }
      }
      @Override public void onFinished(boolean cancelled, boolean keep) {
        runOnUiThread(() -> {
          if (discard) {
            discard = false;
            fetchRunning = false;
            fetchManager = null;
            updateBackgroundTask("");
            setTaskRunning(false);
            cardRunning.setVisibility(View.GONE);
            return;
          }
          List<String> more;
          synchronized (discovered) { more = new ArrayList<>(new LinkedHashSet<>(discovered)); discovered.clear(); }
          if (!cancelled && !followUp && !more.isEmpty()) { startFetch(more, true); return; }
          partial = cancelled && keep;
          recompute(partial);
        });
      }
    });
  }

  private void stopCurrentTask() {
    if (checkRunning) {
      stopCheck();
      return;
    }
    if (fetchRunning) stopParse();
  }

  private void stopCheck() {
    if (checkEngine == null || checkCancelRequested) return;
    checkCancelRequested = true;
    btnStop.setEnabled(false);
    btnStop.setText("正在停止…");
    uiCheckExtra = "正在停止校验，请稍候…";
    flushCheckUi(true);
    checkEngine.cancel();
  }

  private void stopParse() {
    if (fetchManager == null) return;
    new MaterialAlertDialogBuilder(this)
      .setTitle("停止解析？")
      .setItems(new String[]{"处理已加载数据", "全部放弃", "继续解析"}, (d, w) -> {
        if (w == 0) { partial = true; fetchManager.cancel(true); }
        else if (w == 1) { discard = true; fetchManager.cancel(false); }
      }).show();
  }

  private void recompute(boolean isPartial) {
    fetchRunning = false;
    checkRunning = false;
    checkCancelRequested = false;
    updateBackgroundTask("");
    List<SourceRecord> copy; synchronized (buckets) { copy = buckets.all(); }
    DedupeResult base = DedupeEngine.run(copy, mode, cleanNames);
    List<InvalidSource> all = new ArrayList<>(base.getInvalid());
    synchronized (extraInvalid) { all.addAll(extraInvalid); }
    result = new DedupeResult(copy.size(), base.getRetained(), base.getDuplicateGroups(), all);
    if (!checkResults.isEmpty()) {
      applyFastestOutcome(FastestUsableDedupe.apply(result.getRetained(), checkResults, checkSettings.probeRounds, null));
    }
    partial = isPartial;
    setTaskRunning(false);
    cardRunning.setVisibility(View.GONE);
    cardResult.setVisibility(View.VISIBLE);
    boolean hasRetained = result != null && !result.getRetained().isEmpty();
    // 校验入口始终可点击；无书源时 showCheckDialog 会给出明确提示，避免灰色看起来像未实现
    btnCheck.setEnabled(true);
    btnImport.setEnabled(hasRetained);
    btnSave.setEnabled(hasRetained);
    tvStats.setText(ResultSummary.format(mode, buckets.localCount(), buckets.networkCount(), result, isPartial));
    setStatValue(R.id.tileOriginal, result.getOriginalCount());
    setStatValue(R.id.tileDup, result.getDuplicateCount());
    setStatValue(R.id.tileKept, result.getRetained().size());
    setStatValue(R.id.tileBad, result.getInvalid().size());
    if (!checkResults.isEmpty()) renderCheckStats();
    else if (!all.isEmpty() && !hasRetained) {
      // 无有效结果时展示首条错误，便于定位抓取/解析失败
      if (tvCheckElapsed != null) tvCheckElapsed.setVisibility(View.GONE);
      tvCheckStats.setVisibility(View.VISIBLE);
      if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.GONE);
      switchOnlyUsable.setVisibility(View.GONE);
      InvalidSource first = all.get(0);
      String detail = first.getDetail() == null ? first.getKind().name() : first.getDetail();
      if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
      tvCheckStats.setText("错误：" + detail);
      renderKindExportSwitches();
    } else {
      if (tvCheckElapsed != null && lastCheckElapsedMs <= 0L) tvCheckElapsed.setVisibility(View.GONE);
      tvCheckStats.setVisibility(View.GONE);
      if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.GONE);
      switchOnlyUsable.setVisibility(View.GONE);
      renderKindExportSwitches();
    }
  }

  private void setTaskRunning(boolean running) {
    btnParse.setEnabled(!running);
    btnCheck.setEnabled(!running);
    btnImport.setEnabled(!running && result != null && !result.getRetained().isEmpty());
    btnSave.setEnabled(!running && result != null && !result.getRetained().isEmpty());
    btnStop.setEnabled(running && !checkCancelRequested);
    if (!running) btnStop.setText("停止");
    for (int i = 0; i < modeGroup.getChildCount(); i++) modeGroup.getChildAt(i).setEnabled(!running);
    switchCleanNames.setEnabled(!running);
    if (switchCleanLogin != null) switchCleanLogin.setEnabled(!running);
    sliderConcurrency.setEnabled(!running);
    for (KindRow row : kindRows.values()) {
      if (row.sw != null) row.sw.setEnabled(!running);
    }
  }

  private void clearAll() {
    if (fetchManager != null) { discard = true; fetchManager.cancel(false); }
    if (checkEngine != null) checkEngine.cancel();
    synchronized (buckets) { buckets.clearAll(); }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    localFileCount = 0; nextOrderSeq.set(0); result = null; checkResults.clear(); fastestOutcome = null; partial = false;
    urlInbox.clear();
    stopElapsedTicker(false);
    fetchRunning = false; checkRunning = false; checkCancelRequested = false;
    updateBackgroundTask("");
    etUrls.setText(""); tvLocalStatus.setVisibility(View.GONE); cardResult.setVisibility(View.GONE); cardRunning.setVisibility(View.GONE);
    toast("已清空本地和网络书源");
  }

  private void readChosen(Uri u) {
    try (InputStream in = getContentResolver().openInputStream(u); ByteArrayOutputStream o = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192]; int n; while ((n = in.read(b)) >= 0) o.write(b, 0, n);
      addLocalJson(nameOf(u), o.toString(StandardCharsets.UTF_8.name()));
    } catch (Exception e) { toast("文件读取失败：" + e.getMessage()); }
  }

  private String nameOf(Uri u) {
    String name = u.getLastPathSegment();
    try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (i >= 0) name = c.getString(i);
      }
    } catch (Exception ignored) {}
    return name == null ? "json" : name;
  }

  private void addLocalJson(String name, String json) {
    operationMode.start(); mode = operationMode.resultMode();
    int start = nextOrderSeq.getAndAdd(Math.max(1, estimateRecordSlots(json)));
    SourceParser.ParseResult p = SourceParser.parseArray(json, start);
    synchronized (buckets) { buckets.addLocal(p.getRecords()); }
    synchronized (extraInvalid) { extraInvalid.addAll(p.getInvalid()); }
    if (!p.getRecords().isEmpty()) {
      localFileCount++;
      tvLocalStatus.setVisibility(View.VISIBLE);
      tvLocalStatus.setText("已添加本地 JSON：" + localFileCount + " 个文件 · " + buckets.localCount() + " 条书源");
    }
    recompute(false);
  }

  private SharedPreferences checkPrefs() {
    return getSharedPreferences("check", MODE_PRIVATE);
  }

  private void loadCheckSettings() {
    SharedPreferences p = checkPrefs();
    checkSettings.timeoutSeconds = p.getInt("timeout", CheckSourceSettings.DEFAULT_TIMEOUT);
    checkSettings.concurrency = p.getInt("concurrency", CheckSourceSettings.DEFAULT_CONCURRENCY);
    checkSettings.probeRounds = p.getInt("probeRounds", CheckSourceSettings.DEFAULT_PROBE_ROUNDS);
    checkSettings.keyword = p.getString("keyword", CheckSourceSettings.DEFAULT_KEYWORD);
    checkSettings.checkSearch = p.getBoolean("search", true);
    checkSettings.checkDiscovery = p.getBoolean("discovery", true);
    checkSettings.checkInfo = p.getBoolean("info", true);
    checkSettings.checkCategory = p.getBoolean("category", true);
    checkSettings.checkContent = p.getBoolean("content", true);
    checkSettings.checkNovel = p.getBoolean("kindNovel", true);
    checkSettings.checkComic = p.getBoolean("kindComic", true);
    checkSettings.checkVideo = p.getBoolean("kindVideo", true);
    checkSettings.checkAudio = p.getBoolean("kindAudio", true);
    checkSettings.checkFile = p.getBoolean("kindFile", true);
    deleteCaptcha = p.getBoolean("deleteCaptcha", true);
    checkSettings.normalize();
  }

  private void saveCheckSettings() {
    checkSettings.normalize();
    checkPrefs().edit()
        .putInt("timeout", (int) checkSettings.timeoutSeconds)
        .putInt("concurrency", checkSettings.concurrency)
        .putInt("probeRounds", checkSettings.probeRounds)
        .putString("keyword", checkSettings.keyword)
        .putBoolean("search", checkSettings.checkSearch)
        .putBoolean("discovery", checkSettings.checkDiscovery)
        .putBoolean("info", checkSettings.checkInfo)
        .putBoolean("category", checkSettings.checkCategory)
        .putBoolean("content", checkSettings.checkContent)
        .putBoolean("kindNovel", checkSettings.checkNovel)
        .putBoolean("kindComic", checkSettings.checkComic)
        .putBoolean("kindVideo", checkSettings.checkVideo)
        .putBoolean("kindAudio", checkSettings.checkAudio)
        .putBoolean("kindFile", checkSettings.checkFile)
        .putBoolean("deleteCaptcha", deleteCaptcha)
        .apply();
  }

  private void bindCheckDialog(View v) {
    Slider slider = v.findViewById(R.id.sliderTimeout);
    TextView label = v.findViewById(R.id.tvTimeoutLabel);
    Slider checkConcurrencySlider = v.findViewById(R.id.sliderCheckConcurrency);
    TextView checkConcurrencyLabel = v.findViewById(R.id.tvCheckConcurrencyLabel);
    Slider probeSlider = v.findViewById(R.id.sliderProbeRounds);
    TextView probeLabel = v.findViewById(R.id.tvProbeRoundsLabel);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbDiscovery = v.findViewById(R.id.cbDiscovery);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    TextInputLayout tilKeyword = v.findViewById(R.id.tilCheckKeyword);
    TextInputEditText etKeyword = v.findViewById(R.id.etCheckKeyword);
    MaterialCheckBox cbKindNovel = v.findViewById(R.id.cbKindNovel);
    MaterialCheckBox cbKindComic = v.findViewById(R.id.cbKindComic);
    MaterialCheckBox cbKindVideo = v.findViewById(R.id.cbKindVideo);
    MaterialCheckBox cbKindAudio = v.findViewById(R.id.cbKindAudio);
    MaterialCheckBox cbKindFile = v.findViewById(R.id.cbKindFile);

    slider.setValue(checkSettings.timeoutSeconds);
    label.setText("校验超时(秒)：" + checkSettings.timeoutSeconds);
    checkConcurrencySlider.setValueFrom(1);
    checkConcurrencySlider.setValueTo(CheckSourceSettings.MAX_CONCURRENCY);
    float conc = Math.max(1, Math.min(CheckSourceSettings.MAX_CONCURRENCY, checkSettings.concurrency));
    checkConcurrencySlider.setValue(conc);
    checkConcurrencyLabel.setText("并发校验数量：" + Math.round(conc));
    probeSlider.setValueFrom(1);
    probeSlider.setValueTo(CheckSourceSettings.MAX_PROBE_ROUNDS);
    float rounds = Math.max(1, Math.min(CheckSourceSettings.MAX_PROBE_ROUNDS, checkSettings.probeRounds));
    probeSlider.setValue(rounds);
    probeLabel.setText("探测轮数：" + Math.round(rounds));
    cbSearch.setChecked(checkSettings.checkSearch);
    cbDiscovery.setChecked(checkSettings.checkDiscovery);
    cbInfo.setChecked(checkSettings.checkInfo);
    cbCategory.setChecked(checkSettings.checkCategory);
    cbContent.setChecked(checkSettings.checkContent);
    etKeyword.setText(checkSettings.keyword);
    tilKeyword.setEnabled(checkSettings.checkSearch);
    etKeyword.setEnabled(checkSettings.checkSearch);
    cbKindNovel.setChecked(checkSettings.checkNovel);
    cbKindComic.setChecked(checkSettings.checkComic);
    cbKindVideo.setChecked(checkSettings.checkVideo);
    cbKindAudio.setChecked(checkSettings.checkAudio);
    cbKindFile.setChecked(checkSettings.checkFile);
    MaterialCheckBox cbDeleteCaptcha = v.findViewById(R.id.cbDeleteCaptcha);
    if (cbDeleteCaptcha != null) cbDeleteCaptcha.setChecked(deleteCaptcha);
    cbCategory.setEnabled(cbInfo.isChecked());
    cbContent.setEnabled(cbInfo.isChecked() && cbCategory.isChecked());
  }

  private void collectCheckDialog(View v) {
    Slider slider = v.findViewById(R.id.sliderTimeout);
    Slider checkConcurrencySlider = v.findViewById(R.id.sliderCheckConcurrency);
    Slider probeSlider = v.findViewById(R.id.sliderProbeRounds);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbDiscovery = v.findViewById(R.id.cbDiscovery);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    TextInputEditText etKeyword = v.findViewById(R.id.etCheckKeyword);
    checkSettings.timeoutSeconds = Math.round(slider.getValue());
    if (checkSettings.timeoutSeconds <= 0) checkSettings.timeoutSeconds = CheckSourceSettings.DEFAULT_TIMEOUT;
    checkSettings.concurrency = Math.round(checkConcurrencySlider.getValue());
    checkSettings.probeRounds = Math.round(probeSlider.getValue());
    checkSettings.checkSearch = cbSearch.isChecked();
    checkSettings.checkDiscovery = cbDiscovery.isChecked();
    checkSettings.checkInfo = cbInfo.isChecked();
    checkSettings.checkCategory = cbCategory.isChecked();
    checkSettings.checkContent = cbContent.isChecked();
    checkSettings.keyword = etKeyword.getText() == null ? "" : etKeyword.getText().toString();
    checkSettings.checkNovel = ((MaterialCheckBox) v.findViewById(R.id.cbKindNovel)).isChecked();
    checkSettings.checkComic = ((MaterialCheckBox) v.findViewById(R.id.cbKindComic)).isChecked();
    checkSettings.checkVideo = ((MaterialCheckBox) v.findViewById(R.id.cbKindVideo)).isChecked();
    checkSettings.checkAudio = ((MaterialCheckBox) v.findViewById(R.id.cbKindAudio)).isChecked();
    checkSettings.checkFile = ((MaterialCheckBox) v.findViewById(R.id.cbKindFile)).isChecked();
    MaterialCheckBox cbDeleteCaptcha = v.findViewById(R.id.cbDeleteCaptcha);
    if (cbDeleteCaptcha != null) deleteCaptcha = cbDeleteCaptcha.isChecked();
    checkSettings.normalize();
  }

  private void showCheckDialog() {
    if (result == null || result.getRetained().isEmpty()) { toast("请先解析网络源或选择 JSON 文件，再进行校验"); return; }
    BottomSheetDialog dialog = new BottomSheetDialog(this);
    View v = getLayoutInflater().inflate(R.layout.dialog_check_source, null);
    dialog.setContentView(v);
    Slider slider = v.findViewById(R.id.sliderTimeout);
    TextView label = v.findViewById(R.id.tvTimeoutLabel);
    Slider checkConcurrencySlider = v.findViewById(R.id.sliderCheckConcurrency);
    TextView checkConcurrencyLabel = v.findViewById(R.id.tvCheckConcurrencyLabel);
    Slider probeSlider = v.findViewById(R.id.sliderProbeRounds);
    TextView probeLabel = v.findViewById(R.id.tvProbeRoundsLabel);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    TextInputLayout tilKeyword = v.findViewById(R.id.tilCheckKeyword);
    TextInputEditText etKeyword = v.findViewById(R.id.etCheckKeyword);
    bindCheckDialog(v);
    slider.addOnChangeListener((s, value, fromUser) -> label.setText("校验超时(秒)：" + Math.round(value)));
    checkConcurrencySlider.addOnChangeListener((s, value, fromUser) ->
        checkConcurrencyLabel.setText("并发校验数量：" + Math.round(value)));
    probeSlider.addOnChangeListener((s, value, fromUser) ->
        probeLabel.setText("探测轮数：" + Math.round(value)));
    cbSearch.setOnCheckedChangeListener((b, on) -> {
      tilKeyword.setEnabled(on);
      etKeyword.setEnabled(on);
    });
    cbInfo.setOnCheckedChangeListener((b, on) -> { cbCategory.setEnabled(on); if (!on) { cbCategory.setChecked(false); cbContent.setChecked(false);} });
    cbCategory.setOnCheckedChangeListener((b, on) -> { cbContent.setEnabled(on && cbInfo.isChecked()); if (!on) cbContent.setChecked(false); });
    v.findViewById(R.id.btnReset).setOnClickListener(x -> {
      checkSettings.resetToDefaults();
      deleteCaptcha = true;
      saveCheckSettings();
      bindCheckDialog(v);
    });
    v.findViewById(R.id.btnCancel).setOnClickListener(x -> dialog.dismiss());
    v.findViewById(R.id.btnOk).setOnClickListener(x -> {
      collectCheckDialog(v);
      saveCheckSettings();
      dialog.dismiss();
      runCheck(checkSettings);
    });
    dialog.show();
  }

  private void runCheck(CheckSourceSettings settings) {
    List<SourceRecord> targets = new ArrayList<>(result.getRetained());
    fetchRunning = false;
    checkRunning = true;
    checkCancelRequested = false;
    lastCheckElapsedMs = 0L;
    checkResults.clear();
    cardRunning.setVisibility(View.VISIBLE);
    setTaskRunning(true);
    btnStop.setText("停止校验");
    btnStop.setEnabled(true);
    startElapsedTicker();
    resetCheckUiCache(settings, targets.size());
    flushCheckUi(true);
    final CheckSourceEngine engine = new CheckSourceEngine(settings.concurrency);
    checkEngine = engine;
    new Thread(() -> {
      CheckSourceEngine.Listener listener = new CheckSourceEngine.Listener() {
        @Override public void onProgress(int done, int total, String currentName) {
          noteCheckProgress(done, total, currentName);
        }
        @Override public void onProbe(SourceRecord source, int round, int rounds, int inFlight) {
          String name = source == null || source.getName() == null ? "书源" : source.getName();
          noteCheckProbe(name, round, rounds, inFlight);
        }
        @Override public void onItem(CheckSourceResult r) {}
        @Override public void onFinished(List<CheckSourceResult> results) {
          boolean wasCancelled = checkCancelRequested || engine.isCancelled();
          FastestUsableDedupe.Outcome outcome = null;
          if (results != null && !results.isEmpty()) {
            runOnUiThread(() -> {
              uiCheckExtra = "收尾不再重打 HTTP，避免进度完成后又卡很久";
              setTextIfChanged(tvProgress, "正在按已测中位耗时汇总同域最快");
              setTextIfChanged(tvStatus, checkStatusLine(settings, uiCheckExtra));
              updateBackgroundTask("汇总同域最快");
            });
            outcome = FastestUsableDedupe.apply(targets, results, settings.probeRounds, null);
          }
          FastestUsableDedupe.Outcome apply = outcome;
          runOnUiThread(() -> {
            stopElapsedTicker(true);
            checkResults = results;
            checkRunning = false;
            checkCancelRequested = false;
            checkEngine = null;
            updateBackgroundTask("");
            setTaskRunning(false);
            cardRunning.setVisibility(View.GONE);
            if (apply != null) applyFastestOutcome(apply);
            onlyUsable = true;
            switchOnlyUsable.setChecked(true);
            switchOnlyUsable.setVisibility(checkResults.isEmpty() ? View.GONE : View.VISIBLE);
            renderCheckStats();
            String spent = formatElapsed(lastCheckElapsedMs);
            toast(wasCancelled
                ? "校验已停止，保留已完成 " + results.size() + " 条 · 用时 " + spent
                : "校验完成 · 用时 " + spent);
          });
        }
      };
      try {
        engine.checkAll(targets, settings, listener);
      } finally {
        engine.close();
      }
    }, "source-check").start();
  }

  private void resetCheckUiCache(CheckSourceSettings settings, int total) {
    uiCheckSettings = settings;
    uiCheckDone = 0;
    uiCheckTotal = total;
    uiCheckInFlight = 0;
    uiCheckRound = 0;
    uiCheckRounds = settings == null ? 0 : settings.probeRounds;
    uiCheckExtra = "";
    uiCheckProgressPct = -1;
    uiCheckDirty = true;
  }

  private void noteCheckProgress(int done, int total, String currentName) {
    uiCheckDone = done;
    uiCheckTotal = total;
    if (done >= total) {
      uiCheckExtra = "源已探完，正在汇总同域最快结果";
    }
    uiCheckDirty = true;
  }

  private void noteCheckProbe(String name, int round, int rounds, int inFlight) {
    uiCheckRound = round;
    uiCheckRounds = rounds;
    uiCheckInFlight = inFlight;
    uiCheckExtra = "";
    uiCheckDirty = true;
  }

  private void flushCheckUi(boolean force) {
    if (!checkRunning || uiCheckSettings == null) return;
    if (!force && !uiCheckDirty) return;
    uiCheckDirty = false;
    int total = Math.max(1, uiCheckTotal);
    int pct = uiCheckDone * 100 / total;
    if (force || pct != uiCheckProgressPct) {
      uiCheckProgressPct = pct;
      progressBar.setProgressCompat(pct, false);
    }
    setTextIfChanged(tvProgress, "正在校验书源 " + uiCheckDone + " / " + uiCheckTotal);
    setTextIfChanged(tvStatus, checkStatusLine(uiCheckSettings, liveCheckExtra()));
    updateBackgroundTask("后台校验 " + uiCheckDone + " / " + uiCheckTotal);
  }

  private static void setTextIfChanged(TextView view, String text) {
    if (view == null || text == null) return;
    CharSequence cur = view.getText();
    if (cur != null && text.contentEquals(cur)) return;
    view.setText(text);
  }

  private String liveCheckExtra() {
    if (uiCheckExtra != null && !uiCheckExtra.isEmpty()) return uiCheckExtra;
    StringBuilder sb = new StringBuilder();
    int flying = Math.max(0, uiCheckInFlight);
    sb.append("进行中 ").append(flying).append(" 条");
    if (uiCheckRound > 0 && uiCheckRounds > 0) {
      sb.append(" · 第 ").append(uiCheckRound).append("/").append(uiCheckRounds).append(" 轮");
    }
    return sb.toString();
  }

  private String checkStatusLine(CheckSourceSettings settings, String extra) {
    StringBuilder sb = new StringBuilder();
    sb.append("超时 ").append(settings.timeoutSeconds).append("s");
    sb.append(" · 并发 ").append(settings.concurrency);
    sb.append(" · 探测 ").append(settings.probeRounds).append(" 轮");
    if (extra != null && !extra.trim().isEmpty()) sb.append("\n").append(extra.trim());
    return sb.toString();
  }

  private void startElapsedTicker() {
    checkClock.start(System.currentTimeMillis());
    lastCheckElapsedMs = 0L;
    elapsedHandler.removeCallbacks(elapsedTick);
    refreshElapsedView();
    elapsedHandler.postDelayed(elapsedTick, 250);
  }

  private void stopElapsedTicker(boolean keepLast) {
    elapsedHandler.removeCallbacks(elapsedTick);
    if (checkClock.isRunning()) checkClock.stop(System.currentTimeMillis());
    if (keepLast) lastCheckElapsedMs = checkClock.elapsed(System.currentTimeMillis());
    else {
      checkClock.reset();
      lastCheckElapsedMs = 0L;
    }
    refreshElapsedView();
  }

  private void refreshElapsedView() {
    long ms = checkClock.isRunning()
        ? checkClock.elapsed(System.currentTimeMillis())
        : lastCheckElapsedMs;
    String text = getString(R.string.check_elapsed, formatElapsed(Math.max(0L, ms)));
    if (tvElapsed != null) {
      if (checkClock.isRunning()) {
        if (tvElapsed.getVisibility() != View.VISIBLE) tvElapsed.setVisibility(View.VISIBLE);
        setTextIfChanged(tvElapsed, text);
      } else if (tvElapsed.getVisibility() != View.GONE) {
        tvElapsed.setVisibility(View.GONE);
      }
    }
    if (tvCheckElapsed != null) {
      if (!checkClock.isRunning() && lastCheckElapsedMs > 0L) {
        tvCheckElapsed.setVisibility(View.VISIBLE);
        tvCheckElapsed.setText(text);
      } else {
        tvCheckElapsed.setVisibility(View.GONE);
      }
    }
  }

  private static int estimateRecordSlots(String body) {
    if (body == null || body.isEmpty()) return 1;
    int n = 0;
    int from = 0;
    while (true) {
      int i = body.indexOf("bookSourceUrl", from);
      if (i < 0) break;
      n++;
      from = i + 13;
      if (n > 50_000) break;
    }
    return Math.max(1, n);
  }

  private void renderCheckStats() {
    int ok = 0, fail = 0, timeout = 0;
    for (CheckSourceResult r : checkResults) {
      if (r.status == CheckSourceResult.Status.SKIPPED) continue;
      if (r.status == CheckSourceResult.Status.SUCCESS) ok++;
      else if (r.status == CheckSourceResult.Status.TIMEOUT) timeout++;
      else fail++;
    }
    tvCheckStats.setVisibility(View.VISIBLE);
    String extra = "";
    if (fastestOutcome != null) {
      extra = " · 同域淘汰 " + fastestOutcome.removedSlow + " · 接近未淘汰 " + fastestOutcome.closeKept;
    }
    tvCheckStats.setText(getString(R.string.check_result_title) + extra);
    refreshElapsedView();
    if (rowCheckTiles != null) rowCheckTiles.setVisibility(View.VISIBLE);
    setStatValue(R.id.tileOk, ok);
    setStatValue(R.id.tileFail, fail);
    setStatValue(R.id.tileTimeout, timeout);
    renderKindExportSwitches();
  }

  private void bindKindRow(View root, int rowId, SourceKind kind, int colorRes) {
    View rowView = root.findViewById(rowId);
    KindRow row = new KindRow(rowView);
    int color = ContextCompat.getColor(this, colorRes);
    row.rail.setBackgroundColor(color);
    row.bar.setIndicatorColor(color);
    row.title.setText(kind.label);
    row.sw.setChecked(checkPrefs().getBoolean("import" + kind.name(), true));
    row.sw.setOnCheckedChangeListener((b, on) -> {
      checkPrefs().edit().putBoolean("import" + kind.name(), on).apply();
      refreshExportPreview();
    });
    kindRows.put(kind, row);
  }

  private void bindStatTile(View tile, String label, int colorRes) {
    if (tile == null) return;
    TextView value = tile.findViewById(R.id.tvStatValue);
    TextView name = tile.findViewById(R.id.tvStatLabel);
    if (name != null) name.setText(label);
    if (value != null) value.setTextColor(ContextCompat.getColor(this, colorRes));
  }

  private void setStatValue(int tileId, int number) {
    View tile = findViewById(tileId);
    if (tile == null) return;
    TextView value = tile.findViewById(R.id.tvStatValue);
    if (value != null) value.setText(String.valueOf(number));
  }

  private void renderKindExportSwitches() {
    if (boxKindExport == null) return;
    if (result == null || result.getRetained().isEmpty()) {
      boxKindExport.setVisibility(View.GONE);
      return;
    }
    Map<SourceKind, Integer> totals = SourceKind.counts(result.getRetained());
    Map<SourceKind, int[]> checked = new LinkedHashMap<>();
    for (SourceKind k : SourceKind.values()) checked.put(k, new int[]{0, 0});
    for (CheckSourceResult r : checkResults) {
      if (r.status == CheckSourceResult.Status.SKIPPED) continue;
      SourceKind kind = r.kind == null ? SourceKind.NOVEL : r.kind;
      int[] n = checked.get(kind);
      n[1]++;
      if (r.status == CheckSourceResult.Status.SUCCESS) n[0]++;
    }
    boolean any = false;
    for (SourceKind k : SourceKind.values()) {
      KindRow row = kindRows.get(k);
      if (row == null) continue;
      int total = totals.get(k) == null ? 0 : totals.get(k);
      int[] n = checked.get(k);
      if (total <= 0) {
        row.root.setVisibility(View.GONE);
        continue;
      }
      any = true;
      row.root.setVisibility(View.VISIBLE);
      if (!checkResults.isEmpty() && n[1] > 0) {
        int pct = n[1] == 0 ? 0 : Math.round(n[0] * 100f / n[1]);
        row.detail.setText("可用 " + n[0] + " · 共 " + n[1] + " · 通过 " + pct + "%");
        row.bar.setProgressCompat(pct, true);
      } else {
        row.detail.setText("去重后 " + total + " 条，尚未校验");
        row.bar.setProgressCompat(0, false);
      }
    }
    boxKindExport.setVisibility(any ? View.VISIBLE : View.GONE);
    refreshExportPreview();
  }

  private void refreshExportPreview() {
    if (tvExportPreview == null) return;
    int n = exportRecords().size();
    tvExportPreview.setText("当前将导入 / 保存 " + n + " 条");
    if (tvFilterHints == null) return;
    if (result == null) {
      tvFilterHints.setVisibility(View.GONE);
      return;
    }
    int login = LoginDetector.count(result.getRetained());
    int captcha = ExportFilter.captchaCount(checkResults);
    StringBuilder sb = new StringBuilder();
    if (login > 0) {
      sb.append("需要登录 ").append(login).append(cleanLogin ? "（将清理）" : "（已保留）");
    }
    if (captcha > 0) {
      if (sb.length() > 0) sb.append(" · ");
      sb.append("弹窗验证码 ").append(captcha).append(deleteCaptcha ? "（将删除）" : "（已保留）");
    }
    if (sb.length() == 0) {
      tvFilterHints.setVisibility(View.GONE);
    } else {
      tvFilterHints.setVisibility(View.VISIBLE);
      tvFilterHints.setText(sb.toString());
    }
  }

  private Set<SourceKind> selectedExportKinds() {
    Set<SourceKind> allow = new LinkedHashSet<>();
    for (Map.Entry<SourceKind, KindRow> e : kindRows.entrySet()) {
      KindRow row = e.getValue();
      if (row != null && row.root.getVisibility() == View.VISIBLE && row.sw.isChecked()) allow.add(e.getKey());
    }
    return allow;
  }

  private static final class KindRow {
    final View root;
    final MaterialSwitch sw;
    final TextView title;
    final TextView detail;
    final LinearProgressIndicator bar;
    final View rail;
    KindRow(View root) {
      this.root = root;
      this.sw = root.findViewById(R.id.switchKind);
      this.title = root.findViewById(R.id.tvKindTitle);
      this.detail = root.findViewById(R.id.tvKindDetail);
      this.bar = root.findViewById(R.id.barKind);
      this.rail = root.findViewById(R.id.kindRail);
    }
  }

  private List<SourceRecord> exportRecords() {
    if (result == null) return Collections.emptyList();
    return ExportFilter.apply(result.getRetained(), checkResults, cleanLogin, onlyUsable, deleteCaptcha,
        selectedExportKinds());
  }

  private void applyFastestOutcome(FastestUsableDedupe.Outcome outcome) {
    fastestOutcome = outcome;
    if (outcome == null || result == null) return;
    List<DuplicateGroup> groups = new ArrayList<DuplicateGroup>();
    for (DuplicateGroup g : result.getDuplicateGroups()) {
      String reason = g.getReason();
      if (reason != null && (reason.startsWith("同类型同域名仅保留") || "耗时接近，未淘汰".equals(reason))) continue;
      groups.add(g);
    }
    groups.addAll(outcome.groups);
    result = new DedupeResult(result.getOriginalCount(), outcome.retained, groups, result.getInvalid());
    setStatValue(R.id.tileDup, result.getDuplicateCount());
    setStatValue(R.id.tileKept, result.getRetained().size());
  }

  private List<Object> exportPayload() {
    return exportPayload(exportRecords());
  }

  private List<Object> exportPayload(List<SourceRecord> list) {
    if (list == null || list.isEmpty()) return Collections.emptyList();
    List<Object> payload = new ArrayList<>();
    String date = GroupTagger.today();
    Map<SourceKind, Integer> kindCounts = SourceKind.counts(list);
    for (SourceRecord s : list) {
      Map<String, Object> m = new LinkedHashMap<>(s.getRaw());
      m.put("bookSourceName", s.getName());
      Object g = m.get("bookSourceGroup");
      String gs = g == null ? "" : String.valueOf(g);
      SourceKind kind = SourceKind.of(s);
      int kindCount = kindCounts.get(kind) == null ? 0 : kindCounts.get(kind);
      m.put("bookSourceGroup", GroupTagger.apply(gs, kind, kindCount, date));
      // attach check groups if any
      for (CheckSourceResult cr : checkResults) {
        if (cr.source.getUrl() != null && cr.source.getUrl().equals(s.getUrl()) && !cr.groups.isEmpty()) {
          String prefix = (cr.kind == null ? SourceKind.NOVEL : cr.kind).label;
          String cg = prefix + ":" + String.join(",", cr.groups);
          Object comment = m.get("bookSourceComment");
          String c = comment == null ? "" : String.valueOf(comment);
          if (!c.contains(cg)) m.put("bookSourceComment", c.isEmpty() ? cg : c + " | " + cg);
        }
      }
      payload.add(m);
    }
    return payload;
  }

  private String exportJson() {
    return exportJson(exportRecords());
  }

  private String exportJson(List<SourceRecord> list) {
    List<Object> payload = exportPayload(list);
    return payload.isEmpty() ? null : MiniJson.stringify(payload);
  }

  private void importReader() {
    List<SourceRecord> candidates = exportRecords();
    if (candidates.isEmpty()) { toast("没有可导入的数据，请打开要导入的类型开关"); return; }
    String last = getSharedPreferences("import", MODE_PRIVATE).getString("lastReader", "");
    List<ReaderCatalog.App> apps = ReaderCatalog.resolve(getPackageManager(), last);
    if (apps.isEmpty()) {
      toast(getString(R.string.no_reader_installed));
      return;
    }
    if (apps.size() == 1) {
      startReaderImport(candidates, apps.get(0).packageName);
      return;
    }
    CharSequence[] labels = new CharSequence[apps.size()];
    for (int i = 0; i < apps.size(); i++) {
      ReaderCatalog.App app = apps.get(i);
      labels[i] = app.label + "\n" + app.packageName;
    }
    new MaterialAlertDialogBuilder(this)
        .setTitle(R.string.choose_reader)
        .setItems(labels, (d, w) -> startReaderImport(candidates, apps.get(w).packageName))
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  private void startReaderImport(List<SourceRecord> list, String packageName) {
    String json = exportJson(list);
    if (json == null) { toast("没有可导入的数据"); return; }
    try {
      // Reading fetches this URL immediately before showing its native BatchImportDialog.
      // Loopback avoids Binder-size limits and never uploads the source JSON to a third party.
      String importUrl = ReaderImportService.prepare(this, json, 2L * 60L * 1000L);
      Intent view = ReaderCatalog.importIntent(packageName, importUrl);
      startActivity(view);
      getSharedPreferences("import", MODE_PRIVATE).edit().putString("lastReader", packageName).apply();
      toast("将导入 " + list.size() + " 条到阅读，请再勾选确认");
    } catch (Exception e) {
      ReaderImportService.cancel(this);
      toast("打开阅读选择页失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
  }

  private void saveJson() {
    String j = exportJson();
    if (j == null) { toast("没有可保存的数据"); return; }
    pendingSave = j;
    String d = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    createDoc.launch("去重_" + exportRecords().size() + "_" + d + ".json");
  }

  private void addBackgroundTaskChip() {
    backgroundTaskChip = new TextView(this);
    backgroundTaskChip.setTextColor(Color.WHITE);
    backgroundTaskChip.setTextSize(13);
    backgroundTaskChip.setGravity(Gravity.CENTER);
    backgroundTaskChip.setMaxLines(1);
    backgroundTaskChip.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff6750A4);
    background.setCornerRadius(dp(19));
    backgroundTaskChip.setBackground(background);
    backgroundTaskChip.setElevation(dp(6));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(38), Gravity.START | Gravity.BOTTOM);
    params.setMargins(dp(16), 0, 0, dp(68));
    pageContainer.addView(backgroundTaskChip, params);
    backgroundTaskChip.setVisibility(View.GONE);
    backgroundTaskChip.setOnClickListener(view -> {
      TabLayout.Tab tab = tabs.getTabAt(0);
      if (tab != null) tabs.selectTab(tab);
      showDedupe();
    });
  }

  private void updateBackgroundTask(String text) {
    backgroundTaskText = text == null ? "" : text;
    if (backgroundTaskChip == null) return;
    backgroundTaskChip.setText(backgroundTaskText);
    backgroundTaskChip.setVisibility(currentTab == 1 && (fetchRunning || checkRunning) ? View.VISIBLE : View.GONE);
  }

  private void addYckRefreshButton() {
    yckRefreshButton = new TextView(this);
    yckRefreshButton.setText("刷新 ↻");
    yckRefreshButton.setContentDescription("刷新 YCK 页面");
    yckRefreshButton.setTextColor(Color.WHITE);
    yckRefreshButton.setTextSize(14);
    yckRefreshButton.setGravity(Gravity.CENTER);
    yckRefreshButton.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff6750A4);
    background.setCornerRadius(dp(21));
    yckRefreshButton.setBackground(background);
    yckRefreshButton.setElevation(dp(6));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.START | Gravity.BOTTOM);
    params.setMargins(dp(16), 0, 0, dp(16));
    pageContainer.addView(yckRefreshButton, params);
    yckRefreshButton.setVisibility(View.GONE);
    yckRefreshButton.setOnClickListener(view -> refreshYck());
  }

  private void refreshYck() {
    if (yck == null) return;
    yckAutoFellBack = false;
    // 白屏/错误页时绕过旧缓存，并重新加载当前 YCK 页面；没有可用地址时回到站点入口。
    String current = yck.getUrl();
    String target = current != null && YckUrlPolicy.allowed(current) ? current : yckSite.entryUrl();
    yck.stopLoading();
    yck.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
    yck.clearCache(false);
    yckLoaded = true;
    toast("正在刷新 " + yckSite.label());
    yck.loadUrl(target);
  }

  private void addYckSiteButton() {
    yckSiteButton = new TextView(this);
    yckSiteButton.setText(yckSite.label() + " ▾");
    yckSiteButton.setTextColor(Color.WHITE);
    yckSiteButton.setTextSize(14);
    yckSiteButton.setGravity(Gravity.CENTER);
    yckSiteButton.setPadding(dp(14), 0, dp(14), 0);
    GradientDrawable background = new GradientDrawable();
    background.setColor(0xff007AFF);
    background.setCornerRadius(dp(21));
    yckSiteButton.setBackground(background);
    yckSiteButton.setElevation(dp(6));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.END | Gravity.BOTTOM);
    params.setMargins(0, 0, dp(16), dp(16));
    pageContainer.addView(yckSiteButton, params);
    yckSiteButton.setVisibility(View.GONE);
    yckSiteButton.setOnClickListener(view -> {
      PopupMenu menu = new PopupMenu(this, yckSiteButton);
      menu.getMenu().add(0, 0, 0, (yckSite == YckSite.MAIN ? "✓ " : "") + "主站 · www.yckceo.com");
      menu.getMenu().add(0, 1, 1, (yckSite == YckSite.BACKUP ? "✓ " : "") + "备用 · www.yck2026.fun");
      menu.getMenu().add(0, 2, 2, (yckSite == YckSite.RELEASE ? "✓ " : "") + "发布页 · yckceo.vip");
      menu.setOnMenuItemClickListener(item -> {
        selectYckSite(item.getItemId() == 0 ? YckSite.MAIN :
            (item.getItemId() == 1 ? YckSite.BACKUP : YckSite.RELEASE));
        return true;
      });
      menu.show();
    });
  }

  private void selectYckSite(YckSite site) {
    if (site == yckSite) return;
    yckSite = site;
    yckAutoFellBack = false;
    getSharedPreferences("yck", MODE_PRIVATE).edit().putString("site", site.preference()).apply();
    if (yckSiteButton != null) yckSiteButton.setText(site.label() + " ▾");
    yckLoaded = true;
    yck.loadUrl(site.entryUrl());
  }

  private void setupYck() {
    WebSettings s = yck.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    yck.addJavascriptInterface(new YckBridge(this::collectYckUrl), "YckDedupe");
    yckClient = new YckWebClient(new YckWebClient.Listener() {
      public void onJsonLink(String u) { showJsonMenu(u); }
      public void onExternal(String u) { toast("已拦截非 YCK 页面"); }
      public void onLoadError(String u) {
        if (!yckAutoFellBack) {
          yckAutoFellBack = true;
          YckSite other = yckSite == YckSite.BACKUP ? YckSite.MAIN : YckSite.BACKUP;
          if (yckSite == YckSite.RELEASE) other = YckSite.MAIN;
          String oldLabel = yckSite == YckSite.MAIN ? "主站" : (yckSite == YckSite.BACKUP ? "备用站" : "发布页");
          yckSite = other;
          getSharedPreferences("yck", MODE_PRIVATE).edit().putString("site", other.preference()).apply();
          if (yckSiteButton != null) yckSiteButton.setText(other.label() + " ▾");
          toast(oldLabel + "加载失败，已自动切换到" + other.label());
          yck.loadUrl(other.entryUrl());
        } else toast("站点加载失败，请检查网络后重试");
      }
      public void onPageFinished(String u) {
        yckAutoFellBack = false;
        yck.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        injectYckCollector();
      }
    });
    yck.setWebViewClient(yckClient);
    yck.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
      if (YckUrlPolicy.json(url)) showJsonMenu(url);
      else {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception ignored) {}
      }
    });
  }
  private String collectYckUrl(final String url) {
    if (!YckUrlPolicy.collectable(url)) return "invalid";
    boolean added = urlInbox.add(url, urlBoxSnapshot);
    runOnUiThread(this::flushUrlsToBox);
    return added ? "added" : "duplicate";
  }
  private void injectYckCollector() {
    yck.post(() -> yck.evaluateJavascript(YckCollectorScript.source(), null));
    yck.postDelayed(() -> yck.evaluateJavascript(YckCollectorScript.source(), null), 800);
  }

  private boolean appendUrlIfAbsent(String u) {
    CharSequence box = etUrls == null ? "" : etUrls.getText();
    boolean added = urlInbox.add(u, box);
    flushUrlsToBox();
    return added;
  }

  private void showJsonMenu(String url) {
    new MaterialAlertDialogBuilder(this).setTitle("发现书源链接")
      .setItems(new String[]{"添加到去重工具", "在当前页面打开", "复制链接", "取消"}, (d, w) -> {
        if (w == 0) {
          boolean added = appendUrlIfAbsent(url);
          toast(added ? "已添加到去重工具" : "已在列表中");
          showDedupe();
          tabs.selectTab(tabs.getTabAt(0));
        } else if (w == 1) {
          yckClient.allowNextJson();
          yck.loadUrl(url);
        } else if (w == 2) {
          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("书源链接", url));
          toast("链接已复制");
        }
      }).show();
  }

  private void showDedupe() {
    currentTab = 0;
    dedupePage.setVisibility(View.VISIBLE);
    yck.setVisibility(View.INVISIBLE);
    if (yckSiteButton != null) yckSiteButton.setVisibility(View.GONE);
    if (yckRefreshButton != null) yckRefreshButton.setVisibility(View.GONE);
    if (backgroundTaskChip != null) backgroundTaskChip.setVisibility(View.GONE);
  }
  private void showYck() {
    currentTab = 1;
    // 仅切换可见页面；不修改 fetch/check 状态，也不调用 cancel。
    dedupePage.setVisibility(View.INVISIBLE);
    yck.setVisibility(View.VISIBLE);
    if (yckSiteButton != null) yckSiteButton.setVisibility(View.VISIBLE);
    if (yckRefreshButton != null) yckRefreshButton.setVisibility(View.VISIBLE);
    updateBackgroundTask(backgroundTaskText);
    yck.requestFocus();
    yck.requestLayout();
    yck.invalidate();
    if (!yckLoaded) { yckLoaded = true; yck.loadUrl(yckSite.entryUrl()); }
  }

  private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

  @Override protected void onDestroy() {
    elapsedHandler.removeCallbacks(elapsedTick);
    super.onDestroy();
  }

  @Override public void onBackPressed() {
    if (currentTab == 1 && yck.canGoBack()) yck.goBack();
    else if (currentTab == 1) { tabs.selectTab(tabs.getTabAt(0)); showDedupe(); }
    else new MaterialAlertDialogBuilder(this).setTitle("退出确认").setMessage("确定要退出阅读书源去重吗？")
      .setPositiveButton("退出", (d, w) -> finish()).setNegativeButton("取消", null).show();
  }
}
