package com.mina.yuedu;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Installed Reading forks that can handle legado://import/bookSource. */
public final class ReaderCatalog {
  public static final class App {
    public final String packageName;
    public final String label;
    public final boolean known;
    public App(String packageName, String label, boolean known) {
      this.packageName = packageName;
      this.label = label;
      this.known = known;
    }
  }

  static final Map<String, String> KNOWN = knownTable();

  private ReaderCatalog() {}

  public static List<App> resolve(PackageManager pm, String lastPackage) {
    Intent view = importIntent();
    List<ResolveInfo> infos = query(pm, view);
    List<App> apps = new ArrayList<App>();
    List<String> seen = new ArrayList<String>();
    for (ResolveInfo info : infos) {
      if (info == null || info.activityInfo == null) continue;
      String pkg = info.activityInfo.packageName;
      if (pkg == null || pkg.isEmpty() || seen.contains(pkg)) continue;
      seen.add(pkg);
      CharSequence activityLabel = info.loadLabel(pm);
      apps.add(describe(pkg, activityLabel));
    }
    sort(apps, lastPackage);
    return apps;
  }

  public static App describe(String packageName, CharSequence activityLabel) {
    String known = KNOWN.get(packageName);
    if (known != null) return new App(packageName, known, true);
    String label = activityLabel == null ? "" : activityLabel.toString().trim();
    if (label.isEmpty()) label = "未知阅读";
    else if (!label.contains("未知阅读")) label = label + " · 未知阅读";
    return new App(packageName, label, false);
  }

  public static void sort(List<App> apps, String lastPackage) {
    if (apps == null) return;
    final String last = lastPackage == null ? "" : lastPackage;
    Collections.sort(apps, new Comparator<App>() {
      @Override public int compare(App a, App b) {
        boolean al = last.equals(a.packageName);
        boolean bl = last.equals(b.packageName);
        if (al != bl) return al ? -1 : 1;
        if (a.known != b.known) return a.known ? -1 : 1;
        int n = a.label.compareToIgnoreCase(b.label);
        if (n != 0) return n;
        return a.packageName.compareTo(b.packageName);
      }
    });
  }

  public static Intent importIntent() {
    Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("legado://import/bookSource"));
    view.addCategory(Intent.CATEGORY_BROWSABLE);
    return view;
  }

  public static Intent importIntent(String packageName, String srcUrl) {
    Intent view = new Intent(Intent.ACTION_VIEW,
        Uri.parse("legado://import/bookSource").buildUpon().appendQueryParameter("src", srcUrl).build());
    view.addCategory(Intent.CATEGORY_BROWSABLE);
    if (packageName != null && !packageName.isEmpty()) view.setPackage(packageName);
    return view;
  }

  private static List<ResolveInfo> query(PackageManager pm, Intent view) {
    List<ResolveInfo> infos = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY);
    if (infos == null) infos = new ArrayList<ResolveInfo>();
    try {
      List<ResolveInfo> all = pm.queryIntentActivities(view, PackageManager.MATCH_ALL);
      if (all != null) {
        for (ResolveInfo info : all) {
          if (info == null || info.activityInfo == null) continue;
          boolean found = false;
          for (ResolveInfo existing : infos) {
            if (existing.activityInfo != null
                && info.activityInfo.packageName.equals(existing.activityInfo.packageName)) {
              found = true;
              break;
            }
          }
          if (!found) infos.add(info);
        }
      }
    } catch (Throwable ignored) {}
    return infos;
  }

  private static Map<String, String> knownTable() {
    LinkedHashMap<String, String> m = new LinkedHashMap<String, String>();
    m.put("io.legado.app", "阅读 原版");
    m.put("io.legado.app.release", "阅读 原版");
    m.put("io.legado.app.debug", "阅读 原版（调试）");
    m.put("io.legado.app.md3", "阅读 MD3");
    m.put("io.legado.plus", "阅读 Plus / Sigma");
    m.put("io.legado.app.plus", "阅读 Plus / Sigma");
    m.put("io.legado.app.e", "阅读 Plus / Sigma");
    m.put("io.legado.app.t", "阅读 T");
    m.put("io.legado.app.r", "阅读 R");
    m.put("io.legado.app.c", "阅读 C");
    m.put("io.legado.app.max", "阅读 MAX");
    m.put("io.legado.app.sum", "阅读 MAX SUM版");
    m.put("io.legado.app.dandan", "阅读 MAX 蛋蛋版");
    m.put("io.legado.app.cichen", "阅读 MAX 辞晨版");
    m.put("io.legado.app.ng", "阅读 NG");
    m.put("io.legado.app.pp", "阅读 PP");
    m.put("io.legado.app.archive", "阅读 Archive");
    m.put("io.legado.app.beta", "阅读 Beta / 喵公子");
    m.put("io.legado.app.shutiao", "阅读 薯条版");
    m.put("io.legado.app.jingshiro", "Jingshiro 版");
    return Collections.unmodifiableMap(m);
  }
}
