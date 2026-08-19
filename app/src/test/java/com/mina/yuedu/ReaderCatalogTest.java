package com.mina.yuedu;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReaderCatalogTest {
  @Test public void knownPackagesUseFriendlyNames() {
    ReaderCatalog.App original = ReaderCatalog.describe("io.legado.app", "阅读");
    assertEquals("阅读 原版", original.label);
    assertTrue(original.known);
    ReaderCatalog.App md3 = ReaderCatalog.describe("io.legado.app.md3", "阅读");
    assertEquals("阅读 MD3", md3.label);
  }

  @Test public void unknownPackagesAreMarked() {
    ReaderCatalog.App unknown = ReaderCatalog.describe("com.example.reader", "某某阅读");
    assertEquals("某某阅读 · 未知阅读", unknown.label);
    assertFalse(unknown.known);
    ReaderCatalog.App empty = ReaderCatalog.describe("com.foo.bar", "");
    assertEquals("未知阅读", empty.label);
  }

  @Test public void lastChoiceIsPinnedFirst() {
    List<ReaderCatalog.App> apps = new ArrayList<ReaderCatalog.App>();
    apps.add(ReaderCatalog.describe("io.legado.app", "阅读"));
    apps.add(ReaderCatalog.describe("io.legado.app.md3", "阅读"));
    apps.add(ReaderCatalog.describe("com.foo.reader", "第三方"));
    ReaderCatalog.sort(apps, "com.foo.reader");
    assertEquals("com.foo.reader", apps.get(0).packageName);
  }
}