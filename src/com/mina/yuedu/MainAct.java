package com.mina.yuedu;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import com.mina.yuedu.ui.*;
import com.mina.yuedu.network.YckSite;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class MainAct extends Activity implements DedupeController.Host {
  private static final int PICK = 1001, SAVE = 1002;
  private DedupeView dedupe;
  private DedupeController controller;
  private WebView yck;
  private FrameLayout pages;
  private TextView tabD, tabY;
  private String pendingSave;
  private boolean yckLoaded;
  private YckWebClient yckClient;
  private int currentTab;
  private YckSite yckSite;
  private TextView yckSiteButton;
  private boolean yckAutoFellBack;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    // Android 13+ 预测性返回：必须注册返回回调，否则 targetSdk 35+ 上按返回键直接退出 App
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    }
    getWindow().setStatusBarColor(Color.WHITE);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    // Android 15+/targetSdk 35+ 可能强制 edge-to-edge，避免标题顶进状态栏
    if (Build.VERSION.SDK_INT >= 30) {
      try { getWindow().setDecorFitsSystemWindows(true); } catch (Throwable ignored) {}
    }
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(AppleStyles.BG);
    int topPad = AppleStyles.dp(this, 12) + statusBarHeight();
    root.setPadding(AppleStyles.dp(this, 16), topPad, AppleStyles.dp(this, 16), 0);
    TextView title = AppleStyles.text(this, "阅读书源去重", 24, AppleStyles.TEXT);
    title.setTypeface(null, Typeface.BOLD);
    root.addView(title, margin(-1, -2, 0, 4, 0, 12));

    LinearLayout tabs = new LinearLayout(this);
    tabs.setPadding(AppleStyles.dp(this, 3), AppleStyles.dp(this, 3), AppleStyles.dp(this, 3), AppleStyles.dp(this, 3));
    tabs.setBackground(AppleStyles.round(0xffe9e9eb, 10, this));
    tabD = tab("去重", true);
    tabY = tab("YCK", false);
    tabs.addView(tabD, new LinearLayout.LayoutParams(0, AppleStyles.dp(this, 38), 1));
    tabs.addView(tabY, new LinearLayout.LayoutParams(0, AppleStyles.dp(this, 38), 1));
    root.addView(tabs, margin(-1, -2, 0, 0, 0, 10));

    pages = new FrameLayout(this);
    yckSite = YckSite.fromPreference(getSharedPreferences("yck", MODE_PRIVATE).getString("site", "main"));
    dedupe = new DedupeView(this);
    controller = new DedupeController(this, dedupe, this);
    pages.addView(dedupe, new FrameLayout.LayoutParams(-1, -1));
    yck = new WebView(this);
    setupYck();
    yck.setVisibility(View.GONE);
    pages.addView(yck, new FrameLayout.LayoutParams(-1, -1));
    addYckSiteButton();
    root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);

    if (b != null) {
      String input = b.getString("url_input");
      if (input != null) dedupe.setInputText(input);
      controller.restoreOptions((com.mina.yuedu.model.DedupeMode) b.getSerializable("mode"), b.getInt("concurrency", 4), b.getBoolean("clean_names", false));
      String savedYck = b.getString("yck_url");
      if (savedYck != null) { yckLoaded = true; yck.loadUrl(savedYck); }
      String tab = b.getString("tab");
      if ("yck".equals(tab)) showYck();
    }
    if (!yckLoaded) { yckLoaded = true; yck.loadUrl(yckSite.entryUrl()); }
    tabD.setOnClickListener(v -> showDedupe());
    tabY.setOnClickListener(v -> showYck());
  }

  private TextView tab(String s, boolean on) {
    TextView t = AppleStyles.text(this, s, 15, on ? AppleStyles.TEXT : AppleStyles.MUTED);
    t.setGravity(Gravity.CENTER);
    t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    t.setBackground(AppleStyles.round(on ? AppleStyles.CARD : 0x00ffffff, 8, this));
    return t;
  }
  private void selectTab(int which) {
    currentTab = which;
    styleTab(tabD, which == 0);
    styleTab(tabY, which == 1);
  }
  private void styleTab(TextView t, boolean on) {
    t.setTextColor(on ? AppleStyles.TEXT : AppleStyles.MUTED);
    t.setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
    t.setBackground(AppleStyles.round(on ? AppleStyles.CARD : 0x00ffffff, 8, this));
  }
  private void showDedupe() {
    selectTab(0);
    dedupe.setVisibility(View.VISIBLE);
    yck.setVisibility(View.INVISIBLE);
    if (yckSiteButton != null) yckSiteButton.setVisibility(View.GONE);
  }
  private void showYck() {
    selectTab(1);
    dedupe.setVisibility(View.GONE);
    yck.setVisibility(View.VISIBLE);
    if (yckSiteButton != null) yckSiteButton.setVisibility(View.VISIBLE);
    yck.requestFocus(); yck.requestLayout(); yck.invalidate();
    if (!yckLoaded) { yckLoaded = true; yck.loadUrl(yckSite.entryUrl()); }
  }
  private void addYckSiteButton() {
    yckSiteButton = AppleStyles.text(this, yckSite.label() + " ▾", 14, Color.WHITE);
    yckSiteButton.setGravity(Gravity.CENTER);
    yckSiteButton.setPadding(AppleStyles.dp(this, 14), 0, AppleStyles.dp(this, 14), 0);
    yckSiteButton.setBackground(AppleStyles.round(0xff007AFF, 21, this));
    FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, AppleStyles.dp(this, 42), Gravity.RIGHT | Gravity.BOTTOM);
    p.setMargins(0, 0, AppleStyles.dp(this, 16), AppleStyles.dp(this, 16));
    pages.addView(yckSiteButton, p);
    yckSiteButton.setVisibility(View.GONE);
    yckSiteButton.setOnClickListener(v -> {
      PopupMenu m = new PopupMenu(this, yckSiteButton);
      m.getMenu().add(0, 0, 0, (yckSite == YckSite.MAIN ? "✓ " : "") + "主站 · www.yckceo.com");
      m.getMenu().add(0, 1, 1, (yckSite == YckSite.BACKUP ? "✓ " : "") + "备用 · www.yck2026.fun");
      m.getMenu().add(0, 2, 2, (yckSite == YckSite.RELEASE ? "✓ " : "") + "发布页 · yckceo.vip");
      m.setOnMenuItemClickListener(i -> { selectYckSite(i.getItemId() == 0 ? YckSite.MAIN : (i.getItemId() == 1 ? YckSite.BACKUP : YckSite.RELEASE)); return true; });
      m.show();
    });
  }
  private void selectYckSite(YckSite site) {
    if (site == yckSite) return;
    yckSite = site;
    yckAutoFellBack = false;
    getSharedPreferences("yck", MODE_PRIVATE).edit().putString("site", site.preference()).apply();
    yckSiteButton.setText(site.label() + " ▾");
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
    yck.addJavascriptInterface(new YckBridge(url -> collectYckUrl(url)), "YckDedupe");
    yckClient = new YckWebClient(new YckWebClient.Listener() {
      public void onJsonLink(String u) { showJsonMenu(u); }
      public void onExternal(String u) { Toast.makeText(MainAct.this, "已拦截非 YCK 页面", Toast.LENGTH_SHORT).show(); }
      public void onLoadError(String u) {
        if (!yckAutoFellBack) {
          yckAutoFellBack = true;
          YckSite other = yckSite == YckSite.BACKUP ? YckSite.MAIN : YckSite.BACKUP;
          if (yckSite == YckSite.RELEASE) other = YckSite.MAIN;
          Toast.makeText(MainAct.this, (yckSite == YckSite.MAIN ? "主站" : yckSite == YckSite.BACKUP ? "备用站" : "发布页") + "加载失败，已自动切换到" + other.label() + " · " + other.entryUrl(), Toast.LENGTH_LONG).show();
          yckSite = other;
          getSharedPreferences("yck", MODE_PRIVATE).edit().putString("site", other.preference()).apply();
          yckSiteButton.setText(other.label() + " ▾");
          yck.loadUrl(other.entryUrl());
        } else {
          Toast.makeText(MainAct.this, "站点加载失败，请检查网络后重试", Toast.LENGTH_LONG).show();
        }
      }
      public void onPageFinished(String u) { yckAutoFellBack = false; injectYckCollector(); }
    });
    yck.setWebViewClient(yckClient);
    yck.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
      if (com.mina.yuedu.network.YckUrlPolicy.json(url)) showJsonMenu(url);
      else { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} }
    });
  }
  private String collectYckUrl(final String url) {
    if (!com.mina.yuedu.network.YckUrlPolicy.collectable(url)) return "invalid";
    final String[] result = {"invalid"};
    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    runOnUiThread(() -> { result[0] = dedupe.appendUrlIfAbsent(url) ? "added" : "duplicate"; latch.countDown(); });
    try { if (!latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) return "invalid"; }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); return "invalid"; }
    return result[0];
  }
  private void injectYckCollector() {
    yck.post(() -> yck.evaluateJavascript(com.mina.yuedu.network.YckCollectorScript.source(), null));
    yck.postDelayed(() -> yck.evaluateJavascript(com.mina.yuedu.network.YckCollectorScript.source(), null), 800);
  }
  private void showJsonMenu(final String url) {
    new AlertDialog.Builder(this).setTitle("发现书源链接")
      .setItems(new String[]{"添加到去重工具", "在当前页面打开", "复制链接", "取消"}, (d, w) -> {
        if (w == 0) { dedupe.appendUrl(url); showDedupe(); }
        else if (w == 1) { yckClient.allowNextJson(); yck.loadUrl(url); }
        else if (w == 2) {
          ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("书源链接", url));
          Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
        }
      }).show();
  }
  @Override public void chooseFiles() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("application/json");
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    startActivityForResult(i, PICK);
  }
  @Override public void importReader(String json) {
    try {
      File f = new File(getCacheDir(), "import.json");
      write(f, json);
      Uri u = Uri.parse("content://com.mina.yuedu.cache/import.json");
      Intent i = new Intent(Intent.ACTION_VIEW);
      i.setDataAndType(u, "application/json");
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(i, "导入到阅读"));
    } catch (Exception e) {
      Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }
  @Override public void saveJson(String name, String json) {
    pendingSave = json;
    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("application/json");
    i.putExtra(Intent.EXTRA_TITLE, name);
    startActivityForResult(i, SAVE);
  }
  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (res != RESULT_OK || data == null) return;
    if (req == PICK) {
      if (data.getClipData() != null) {
        for (int i = 0; i < data.getClipData().getItemCount(); i++) readChosen(data.getClipData().getItemAt(i).getUri());
      } else if (data.getData() != null) readChosen(data.getData());
    } else if (req == SAVE && data.getData() != null) {
      try {
        OutputStream o = getContentResolver().openOutputStream(data.getData());
        o.write(pendingSave.getBytes(StandardCharsets.UTF_8));
        o.close();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
      } catch (Exception e) {
        Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
      }
    }
  }
  private void readChosen(Uri u) {
    try {
      InputStream in = getContentResolver().openInputStream(u);
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      byte[] b = new byte[8192]; int n;
      while ((n = in.read(b)) >= 0) o.write(b, 0, n);
      in.close();
      controller.addLocalJson(u.getLastPathSegment(), o.toString("UTF-8"));
    } catch (Exception e) {
      Toast.makeText(this, "文件读取失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }
  private void write(File f, String s) throws Exception {
    FileOutputStream o = new FileOutputStream(f);
    o.write(s.getBytes(StandardCharsets.UTF_8));
    o.close();
  }
  private int statusBarHeight() {
    int h = 0;
    int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
    if (resId > 0) h = getResources().getDimensionPixelSize(resId);
    if (h <= 0) h = AppleStyles.dp(this, 24);
    return h;
  }
  private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
    p.setMargins(l, t, r, b);
    return p;
  }
  @Override protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    String tab = currentTab == 1 ? "yck" : "dedupe";
    out.putString("tab", tab);
    out.putString("url_input", dedupe.getInputText());
    out.putSerializable("mode", controller.getMode());
    out.putInt("concurrency", controller.getConcurrency());
    out.putBoolean("clean_names", controller.isCleanNames());
    if (yck.getUrl() != null) out.putString("yck_url", yck.getUrl());
  }
  @Override public void onBackPressed() { handleBack(); }
  private void handleBack() {
    if (currentTab == 1 && yck.getVisibility() == View.VISIBLE) {
      if (yck.canGoBack()) {
        yck.goBack();
        yck.postDelayed(() -> { yck.requestFocus(); yck.requestLayout(); yck.invalidate(); }, 100);
      } else {
        showDedupe();
      }
    } else {
      confirmExit();
    }
  }
  private void confirmExit() {
    new AlertDialog.Builder(this)
      .setTitle("退出确认")
      .setMessage("确定要退出阅读书源去重吗？")
      .setPositiveButton("退出", (d, w) -> finish())
      .setNegativeButton("取消", null)
      .show();
  }
}
