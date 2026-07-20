package com.mina.yuedu;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.mina.yuedu.network.YckSite;
import com.mina.yuedu.ui.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainAct extends Activity implements DedupeController.Host {
    private static final int PICK = 1001, SAVE = 1002;

    private DedupeView dedupe;
    private DedupeController controller;
    private WebView yck;
    private FrameLayout pages;
    private TextView tabD, tabY;
    private boolean yckLoaded;
    private YckWebClient yckClient;
    private boolean dedupeTab = true;
    private YckSite yckSite;
    private TextView yckSiteButton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AppleStyles.BG);
        root.setPadding(AppleStyles.dp(this, 16), AppleStyles.dp(this, 12), AppleStyles.dp(this, 16), 0);

        TextView title = AppleStyles.text(this, "阅读书源去重", 24, AppleStyles.TEXT);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title, margin(-1, -2, 0, 4, 0, 12));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(AppleStyles.dp(this, 3), AppleStyles.dp(this, 3), AppleStyles.dp(this, 3), AppleStyles.dp(this, 3));
        tabs.setBackground(AppleStyles.round(0xffe9e9eb, 10, this));
        tabD = tab("去重工具", true);
        tabY = tab("YCK书源", false);
        tabs.addView(tabD, new LinearLayout.LayoutParams(0, AppleStyles.dp(this, 38), 1));
        tabs.addView(tabY, new LinearLayout.LayoutParams(0, AppleStyles.dp(this, 38), 1));
        root.addView(tabs, margin(-1, -2, 0, 0, 0, 10));

        pages = new FrameLayout(this);
        yckSite = YckSite.fromPreference(getSharedPreferences("yck", MODE_PRIVATE).getString("site", "main"));
        dedupe = new DedupeView(this);
        controller = new DedupeController(this, dedupe, this);
        pages.addView(dedupe, new FrameLayout.LayoutParams(-1, -1));
        root.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        if (b != null) {
            String input = b.getString("url_input");
            if (input != null) dedupe.setInputText(input);
            controller.restoreOptions(
                    (com.mina.yuedu.model.DedupeMode) b.getSerializable("mode"),
                    b.getInt("concurrency", 4),
                    b.getBoolean("clean_names", false)
            );
            if ("yck".equals(b.getString("tab"))) showYck();
        }

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

    private void select(boolean d) {
        tabD.setTextColor(d ? AppleStyles.TEXT : AppleStyles.MUTED);
        tabY.setTextColor(d ? AppleStyles.MUTED : AppleStyles.TEXT);
        tabD.setTypeface(null, d ? Typeface.BOLD : Typeface.NORMAL);
        tabY.setTypeface(null, d ? Typeface.NORMAL : Typeface.BOLD);
        tabD.setBackground(AppleStyles.round(d ? AppleStyles.CARD : 0x00ffffff, 8, this));
        tabY.setBackground(AppleStyles.round(d ? 0x00ffffff : AppleStyles.CARD, 8, this));
    }

    private void showDedupe() {
        dedupeTab = true;
        select(true);
        dedupe.setVisibility(View.VISIBLE);
        if (yck != null) yck.setVisibility(View.INVISIBLE);
        if (yckSiteButton != null) yckSiteButton.setVisibility(View.GONE);
    }

    private void showYck() {
        dedupeTab = false;
        select(false);
        ensureYckCreated();
        dedupe.setVisibility(View.GONE);
        yck.setVisibility(View.VISIBLE);
        if (yckSiteButton != null) yckSiteButton.setVisibility(View.VISIBLE);
        yck.requestFocus();
        yck.requestLayout();
        yck.invalidate();
        if (!yckLoaded) {
            yckLoaded = true;
            yck.loadUrl(yckSite.entryUrl());
        }
    }

    private void ensureYckCreated() {
        if (yck != null) return;
        yck = new WebView(this);
        setupYck();
        yck.setVisibility(View.GONE);
        pages.addView(yck, new FrameLayout.LayoutParams(-1, -1));
        addYckSiteButton();
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
            m.getMenu().add(0, 1, 1, (yckSite == YckSite.BACKUP ? "✓ " : "") + "备用 · www.yck2026.top");
            m.setOnMenuItemClickListener(i -> {
                selectYckSite(i.getItemId() == 0 ? YckSite.MAIN : YckSite.BACKUP);
                return true;
            });
            m.show();
        });
    }

    private void selectYckSite(YckSite site) {
        if (site == yckSite) return;
        yckSite = site;
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
            public void onJsonLink(String u) {
                showJsonMenu(u);
            }

            public void onExternal(String u) {
                Toast.makeText(MainAct.this, "已拦截非 YCK 页面", Toast.LENGTH_SHORT).show();
            }

            public void onLoadError(String u) {
                Toast.makeText(MainAct.this, "站点加载失败，请切换备用站", Toast.LENGTH_SHORT).show();
            }

            public void onPageFinished(String u) {
                injectYckCollector();
            }
        });
        yck.setWebViewClient(yckClient);
        yck.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) -> {
            if (com.mina.yuedu.network.YckUrlPolicy.json(url)) showJsonMenu(url);
            else {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
        });
    }

    private String collectYckUrl(final String url) {
        if (!com.mina.yuedu.network.YckUrlPolicy.collectable(url)) return "invalid";
        final String[] result = {"invalid"};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            result[0] = dedupe.appendUrlIfAbsent(url) ? "added" : "duplicate";
            latch.countDown();
        });
        try {
            if (!latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)) return "invalid";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "invalid";
        }
        return result[0];
    }

    private void injectYckCollector() {
        yck.post(() -> yck.evaluateJavascript(com.mina.yuedu.network.YckCollectorScript.source(), null));
        yck.postDelayed(() -> yck.evaluateJavascript(com.mina.yuedu.network.YckCollectorScript.source(), null), 800);
    }

    private void showJsonMenu(final String url) {
        new AlertDialog.Builder(this)
                .setTitle("发现书源链接")
                .setItems(new String[]{"添加到去重工具", "在当前页面打开", "复制链接", "取消"}, (d, w) -> {
                    if (w == 0) {
                        dedupe.appendUrl(url);
                        showDedupe();
                    } else if (w == 1) {
                        yckClient.allowNextJson();
                        yck.loadUrl(url);
                    } else if (w == 2) {
                        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                .setPrimaryClip(android.content.ClipData.newPlainText("书源链接", url));
                        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    @Override
    public void chooseFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override
    public void importReaderFromCache(File file) {
        try {
            Uri u = Uri.parse("content://com.mina.yuedu.cache/" + file.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/json");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "导入到阅读"));
        } catch (Exception e) {
            Toast.makeText(this, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void saveJson(String name) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, SAVE);
    }

    @Override
    public ContentResolver contentResolver() {
        return getContentResolver();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == PICK) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            controller.importLocalUris(uris);
        } else if (req == SAVE && data.getData() != null) {
            controller.saveToUri(data.getData());
        }
    }

    private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("tab", dedupeTab ? "dedupe" : "yck");
        out.putString("url_input", dedupe.getInputText());
        out.putSerializable("mode", controller.getMode());
        out.putInt("concurrency", controller.getConcurrency());
        out.putBoolean("clean_names", controller.isCleanNames());
        if (yck != null && yck.getUrl() != null) out.putString("yck_url", yck.getUrl());
    }

    @Override
    public void onBackPressed() {
        if (yck != null && yck.getVisibility() == View.VISIBLE) {
            if (yck.canGoBack()) {
                yck.goBack();
                yck.postDelayed(() -> {
                    yck.requestFocus();
                    yck.requestLayout();
                    yck.invalidate();
                }, 100);
            } else showDedupe();
        } else super.onBackPressed();
    }
}
