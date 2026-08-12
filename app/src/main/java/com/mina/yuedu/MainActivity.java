package com.mina.yuedu;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
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
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.mina.yuedu.check.*;
import com.mina.yuedu.core.*;
import com.mina.yuedu.model.*;
import com.mina.yuedu.network.*;
import com.mina.yuedu.ui.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends AppCompatActivity {
  private View dedupePage;
  private WebView yck;
  private TextView yckSiteButton, yckRefreshButton, backgroundTaskChip;
  private FrameLayout pageContainer;
  private TabLayout tabs;
  private TextInputEditText etUrls;
  private TextView tvLocalStatus, tvModeDesc, tvProgress, tvStatus, tvStats, tvCheckStats;
  private View cardRunning, cardResult;
  private LinearProgressIndicator progressBar;
  private MaterialSwitch switchCleanNames, switchOnlyUsable;
  private MaterialButtonToggleGroup modeGroup;
  private Slider sliderConcurrency;
  private MaterialButton btnParse, btnStop, btnCheck, btnImport, btnSave;

  private final SourceBuckets buckets = new SourceBuckets();
  private final List<InvalidSource> extraInvalid = new ArrayList<>();
  private final List<String> discovered = new ArrayList<>();
  private final OperationMode operationMode = new OperationMode(DedupeMode.STANDARD);
  private DedupeMode mode = DedupeMode.STANDARD;
  private DedupeResult result;
  private List<CheckSourceResult> checkResults = new ArrayList<>();
  private FetchManager fetchManager;
  private CheckSourceEngine checkEngine;
  private boolean cleanNames, partial, discard, onlyUsable;
  private boolean fetchRunning, checkRunning, checkCancelRequested;
  private int concurrency = 4, checkConcurrency = 8, nextOrder, localFileCount, currentTab;
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
    checkConcurrency = getSharedPreferences("check", MODE_PRIVATE).getInt("concurrency", 8);
    if (checkConcurrency < 1 || checkConcurrency > CheckSourceSettings.MAX_CONCURRENCY) checkConcurrency = 8;

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
    tvLocalStatus = root.findViewById(R.id.tvLocalStatus);
    tvModeDesc = root.findViewById(R.id.tvModeDesc);
    tvProgress = root.findViewById(R.id.tvProgress);
    tvStatus = root.findViewById(R.id.tvStatus);
    tvStats = root.findViewById(R.id.tvStats);
    tvCheckStats = root.findViewById(R.id.tvCheckStats);
    cardRunning = root.findViewById(R.id.cardRunning);
    cardResult = root.findViewById(R.id.cardResult);
    progressBar = root.findViewById(R.id.progressBar);
    switchCleanNames = root.findViewById(R.id.switchCleanNames);
    switchOnlyUsable = root.findViewById(R.id.switchOnlyUsable);
    modeGroup = root.findViewById(R.id.modeGroup);
    sliderConcurrency = root.findViewById(R.id.sliderConcurrency);
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
    sliderConcurrency.addOnChangeListener((s, value, fromUser) -> concurrency = Math.round(value));
    switchCleanNames.setOnCheckedChangeListener((b, on) -> { cleanNames = on; if (result != null) recompute(partial); });
    switchOnlyUsable.setOnCheckedChangeListener((b, on) -> onlyUsable = on);
    btnParse.setOnClickListener(v -> startParse());
    btnStop.setOnClickListener(v -> stopCurrentTask());
    btnCheck.setOnClickListener(v -> showCheckDialog());
    btnImport.setOnClickListener(v -> importReader());
    btnSave.setOnClickListener(v -> saveJson());
  }

  private void updateModeDesc() { tvModeDesc.setText(mode.description()); }

  private List<String> getUrls() {
    List<String> x = new ArrayList<>();
    CharSequence cs = etUrls.getText();
    if (cs == null) return x;
    for (String s : cs.toString().split("\\r?\\n")) if (!s.trim().isEmpty()) x.add(s.trim());
    return x;
  }

  private void startParse() {
    operationMode.select(mode); operationMode.start(); mode = operationMode.resultMode();
    cleanNames = switchCleanNames.isChecked();
    concurrency = Math.round(sliderConcurrency.getValue());
    partial = false; discard = false; checkResults.clear();
    synchronized (buckets) { buckets.replaceNetwork(Collections.emptyList()); }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    List<String> urls = getUrls();
    if (!ParseRequestDecision.shouldRun(buckets.localCount(), urls.size())) { toast("请选择本地文件或输入网络地址"); return; }
    if (urls.isEmpty()) { recompute(false); return; }
    startFetch(urls, false);
  }

  private void startFetch(List<String> urls, boolean followUp) {
    fetchRunning = true;
    checkRunning = false;
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
          progressBar.setProgressCompat(p.getCompleted() * 100 / total, true);
          tvProgress.setText("正在解析书源    " + p.getCompleted() + " / " + p.getTotal());
          tvStatus.setText("成功 " + p.getSucceeded() + " · 失败 " + p.getFailed() + " · 已发现 " + p.getDiscoveredSources() + " 条");
          updateBackgroundTask("后台解析 " + p.getCompleted() + " / " + p.getTotal());
        });
      }
      @Override public void onItem(String url, String body) {
        SourceParser.ParseResult p = SourceParser.parseArray(body, nextOrder);
        synchronized (buckets) {
          if (!p.getRecords().isEmpty()) {
            buckets.addNetwork(p.getRecords());
            nextOrder += p.getRecords().size();
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
    tvStatus.setText("正在停止校验，请稍候…");
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
    if (!checkResults.isEmpty()) renderCheckStats();
    else if (!all.isEmpty() && !hasRetained) {
      // 无有效结果时展示首条错误，便于定位抓取/解析失败
      tvCheckStats.setVisibility(View.VISIBLE);
      switchOnlyUsable.setVisibility(View.GONE);
      InvalidSource first = all.get(0);
      String detail = first.getDetail() == null ? first.getKind().name() : first.getDetail();
      if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
      tvCheckStats.setText("错误：" + detail);
    } else {
      tvCheckStats.setVisibility(View.GONE);
      switchOnlyUsable.setVisibility(View.GONE);
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
    sliderConcurrency.setEnabled(!running);
  }

  private void clearAll() {
    if (fetchManager != null) { discard = true; fetchManager.cancel(false); }
    if (checkEngine != null) checkEngine.cancel();
    synchronized (buckets) { buckets.clearAll(); }
    synchronized (extraInvalid) { extraInvalid.clear(); }
    synchronized (discovered) { discovered.clear(); }
    localFileCount = 0; nextOrder = 0; result = null; checkResults.clear(); partial = false;
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
    SourceParser.ParseResult p = SourceParser.parseArray(json, nextOrder);
    synchronized (buckets) { buckets.addLocal(p.getRecords()); }
    synchronized (extraInvalid) { extraInvalid.addAll(p.getInvalid()); }
    nextOrder += p.getRecords().size() + p.getInvalid().size();
    if (!p.getRecords().isEmpty()) {
      localFileCount++;
      tvLocalStatus.setVisibility(View.VISIBLE);
      tvLocalStatus.setText("已添加本地 JSON：" + localFileCount + " 个文件 · " + buckets.localCount() + " 条书源");
    }
    recompute(false);
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
    checkConcurrencySlider.setValue(checkConcurrency);
    checkConcurrencyLabel.setText("并发校验数量：" + checkConcurrency);
    MaterialCheckBox cbSearch = v.findViewById(R.id.cbSearch);
    MaterialCheckBox cbDiscovery = v.findViewById(R.id.cbDiscovery);
    MaterialCheckBox cbInfo = v.findViewById(R.id.cbInfo);
    MaterialCheckBox cbCategory = v.findViewById(R.id.cbCategory);
    MaterialCheckBox cbContent = v.findViewById(R.id.cbContent);
    slider.addOnChangeListener((s, value, fromUser) -> label.setText("校验超时(秒)：" + Math.round(value)));
    checkConcurrencySlider.addOnChangeListener((s, value, fromUser) ->
        checkConcurrencyLabel.setText("并发校验数量：" + Math.round(value)));
    cbInfo.setOnCheckedChangeListener((b, on) -> { cbCategory.setEnabled(on); if (!on) { cbCategory.setChecked(false); cbContent.setChecked(false);} });
    cbCategory.setOnCheckedChangeListener((b, on) -> { cbContent.setEnabled(on && cbInfo.isChecked()); if (!on) cbContent.setChecked(false); });
    v.findViewById(R.id.btnCancel).setOnClickListener(x -> dialog.dismiss());
    v.findViewById(R.id.btnOk).setOnClickListener(x -> {
      CheckSourceSettings settings = new CheckSourceSettings();
      settings.timeoutSeconds = Math.round(slider.getValue());
      if (settings.timeoutSeconds <= 0) settings.timeoutSeconds = 180;
      settings.checkSearch = cbSearch.isChecked();
      settings.checkDiscovery = cbDiscovery.isChecked();
      settings.checkInfo = cbInfo.isChecked();
      settings.checkCategory = cbCategory.isChecked();
      settings.checkContent = cbContent.isChecked();
      checkConcurrency = Math.round(checkConcurrencySlider.getValue());
      settings.concurrency = checkConcurrency;
      getSharedPreferences("check", MODE_PRIVATE).edit().putInt("concurrency", checkConcurrency).apply();
      settings.normalize();
      dialog.dismiss();
      runCheck(settings);
    });
    dialog.show();
  }

  private void runCheck(CheckSourceSettings settings) {
    List<SourceRecord> targets = new ArrayList<>(result.getRetained());
    fetchRunning = false;
    checkRunning = true;
    checkCancelRequested = false;
    checkResults.clear();
    cardRunning.setVisibility(View.VISIBLE);
    setTaskRunning(true);
    btnStop.setText("停止校验");
    btnStop.setEnabled(true);
    updateBackgroundTask("后台校验 0 / " + targets.size());
    tvProgress.setText("正在校验书源 0 / " + targets.size());
    tvStatus.setText("超时 " + settings.timeoutSeconds + "s · 并发 " + settings.concurrency);
    progressBar.setProgressCompat(0, false);
    checkEngine = new CheckSourceEngine(settings.concurrency);
    new Thread(() -> checkEngine.checkAll(targets, settings, new CheckSourceEngine.Listener() {
      @Override public void onProgress(int done, int total, String currentName) {
        runOnUiThread(() -> {
          progressBar.setProgressCompat(done * 100 / Math.max(1, total), true);
          tvProgress.setText("正在校验书源 " + done + " / " + total);
          tvStatus.setText(currentName == null ? "" : currentName);
          updateBackgroundTask("后台校验 " + done + " / " + total);
        });
      }
      @Override public void onItem(CheckSourceResult r) {}
      @Override public void onFinished(List<CheckSourceResult> results) {
        runOnUiThread(() -> {
          boolean wasCancelled = checkCancelRequested || (checkEngine != null && checkEngine.isCancelled());
          checkResults = results;
          checkRunning = false;
          checkCancelRequested = false;
          checkEngine = null;
          updateBackgroundTask("");
          setTaskRunning(false);
          cardRunning.setVisibility(View.GONE);
          switchOnlyUsable.setVisibility(checkResults.isEmpty() ? View.GONE : View.VISIBLE);
          renderCheckStats();
          toast(wasCancelled ? "校验已停止，保留已完成 " + results.size() + " 条" : "校验完成");
        });
      }
    }), "source-check").start();
  }

  private void renderCheckStats() {
    int ok = 0, fail = 0, timeout = 0;
    for (CheckSourceResult r : checkResults) {
      if (r.status == CheckSourceResult.Status.SUCCESS) ok++;
      else if (r.status == CheckSourceResult.Status.TIMEOUT) timeout++;
      else fail++;
    }
    tvCheckStats.setVisibility(View.VISIBLE);
    tvCheckStats.setText("校验：可用 " + ok + " · 失败 " + fail + " · 超时 " + timeout);
  }

  private List<SourceRecord> exportRecords() {
    if (result == null) return Collections.emptyList();
    if (!onlyUsable || checkResults.isEmpty()) return result.getRetained();
    Set<String> good = new HashSet<>();
    for (CheckSourceResult r : checkResults) if (r.isUsable() && r.source.getUrl() != null) good.add(r.source.getUrl());
    List<SourceRecord> out = new ArrayList<>();
    for (SourceRecord s : result.getRetained()) if (s.getUrl() != null && good.contains(s.getUrl())) out.add(s);
    return out;
  }

  private List<Object> exportPayload() {
    List<SourceRecord> list = exportRecords();
    if (list.isEmpty()) return Collections.emptyList();
    List<Object> payload = new ArrayList<>();
    String stamp = new SimpleDateFormat("yyyy-M-d", Locale.CHINA).format(new Date());
    String mark = "✔" + stamp + "检验去重（优质" + list.size() + "）";
    for (SourceRecord s : list) {
      Map<String, Object> m = new LinkedHashMap<>(s.getRaw());
      m.put("bookSourceName", s.getName());
      Object g = m.get("bookSourceGroup");
      String gs = g == null ? "" : String.valueOf(g);
      // replace previous inspection marks to avoid stacking
      gs = gs.replaceAll("(?:,)?✔\\d{4}-\\d{1,2}-\\d{1,2}检验去重（优质\\d+）", "");
      if (gs.startsWith(",")) gs = gs.substring(1);
      m.put("bookSourceGroup", gs.isEmpty() ? mark : gs + "," + mark);
      // attach check groups if any
      for (CheckSourceResult cr : checkResults) {
        if (cr.source.getUrl() != null && cr.source.getUrl().equals(s.getUrl()) && !cr.groups.isEmpty()) {
          String cg = String.join(",", cr.groups);
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
    List<Object> payload = exportPayload();
    return payload.isEmpty() ? null : MiniJson.stringify(payload);
  }

  private void importReader() {
    String json = exportJson();
    if (json == null) { toast("没有可导入的数据"); return; }
    try {
      // Reading fetches this URL immediately before showing its native BatchImportDialog.
      // Loopback avoids Binder-size limits and never uploads the source JSON to a third party.
      String importUrl = ReaderImportService.prepare(this, json, 2L * 60L * 1000L);
      Uri deepLink = Uri.parse("legado://import/bookSource").buildUpon()
          .appendQueryParameter("src", importUrl)
          .build();
      Intent view = new Intent(Intent.ACTION_VIEW, deepLink);
      view.addCategory(Intent.CATEGORY_BROWSABLE);
      List<android.content.pm.ResolveInfo> readers = getPackageManager().queryIntentActivities(
          view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
      if (readers.isEmpty()) throw new ActivityNotFoundException("未找到支持 legado:// 导入的阅读 App");
      startActivity(view);
      toast("请在阅读中勾选需要导入的书源");
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
    final String[] out = {"invalid"};
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    runOnUiThread(() -> {
      boolean added = appendUrlIfAbsent(url);
      out[0] = added ? "added" : "duplicate";
      // 与 2.3.10 一致：仅写入 URL 列表，不强制切 Tab；由注入按钮 flash 反馈
      latch.countDown();
    });
    try {
      if (!latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) return "invalid";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "invalid";
    }
    return out[0];
  }
  private void injectYckCollector() {
    yck.post(() -> yck.evaluateJavascript(YckCollectorScript.source(), null));
    yck.postDelayed(() -> yck.evaluateJavascript(YckCollectorScript.source(), null), 800);
  }

  private boolean appendUrlIfAbsent(String u) {
    String clean = u == null ? "" : u.trim();
    if (clean.isEmpty()) return false;
    CharSequence cur = etUrls.getText();
    String s = cur == null ? "" : cur.toString();
    for (String x : s.split("\\r?\\n")) if (x.trim().equals(clean)) return false;
    etUrls.setText(s.trim().isEmpty() ? clean : s + "\n" + clean);
    return true;
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

  @Override public void onBackPressed() {
    if (currentTab == 1 && yck.canGoBack()) yck.goBack();
    else if (currentTab == 1) { tabs.selectTab(tabs.getTabAt(0)); showDedupe(); }
    else new MaterialAlertDialogBuilder(this).setTitle("退出确认").setMessage("确定要退出阅读书源去重吗？")
      .setPositiveButton("退出", (d, w) -> finish()).setNegativeButton("取消", null).show();
  }
}
