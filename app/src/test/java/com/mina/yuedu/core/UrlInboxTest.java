package com.mina.yuedu.core;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UrlInboxTest {
  @Test public void yckAddsDoNotWaitOnTextBox() {
    UrlInbox inbox = new UrlInbox();
    assertTrue(inbox.add("https://a.example/1.json"));
    assertTrue(inbox.add("https://b.example/2.json"));
    assertFalse(inbox.add("https://a.example/1.json"));
    List<String> all = inbox.merge("");
    assertEquals(2, all.size());
  }

  @Test public void mergeKeepsTypedBoxAndInbox() {
    UrlInbox inbox = new UrlInbox();
    inbox.add("https://c.example/3.json");
    List<String> all = inbox.merge("https://a.example/1.json\nhttps://b.example/2.json");
    assertEquals(3, all.size());
    assertEquals("https://a.example/1.json", all.get(0));
    assertEquals("https://c.example/3.json", all.get(2));
  }

  @Test public void addDoesNotDuplicateWhatIsAlreadyInTheBox() {
    UrlInbox inbox = new UrlInbox();
    assertFalse(inbox.add("https://a.example/1.json", "https://a.example/1.json\nhttps://b.example/2.json"));
    assertTrue(inbox.add("https://c.example/3.json", "https://a.example/1.json\nhttps://b.example/2.json"));
    assertEquals(3, inbox.merge("https://a.example/1.json\nhttps://b.example/2.json").size());
  }

  @Test public void takeAllKeepsInboxUrlsEvenIfBoxIsEmpty() {
    UrlInbox inbox = new UrlInbox();
    inbox.add("https://a.example/1.json");
    inbox.add("https://b.example/2.json");
    inbox.add("https://c.example/3.json");
    List<String> all = inbox.takeAll("");
    assertEquals(3, all.size());
    assertTrue(inbox.merge("").isEmpty());
  }

  @Test public void concurrentAddsKeepAllThree() throws Exception {
    UrlInbox inbox = new UrlInbox();
    String[] urls = {
        "https://a.example/1.json",
        "https://b.example/2.json",
        "https://c.example/3.json"
    };
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(urls.length);
    AtomicInteger added = new AtomicInteger();
    for (String u : urls) {
      new Thread(() -> {
        try {
          start.await();
          if (inbox.add(u)) added.incrementAndGet();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      }).start();
    }
    start.countDown();
    assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(3, added.get());
    assertEquals(3, inbox.merge("").size());
  }
}