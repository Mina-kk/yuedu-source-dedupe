package com.mina.yuedu.network;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
public final class FetchManager {
  public static final class QueueState {
    private final Deque<String> pending; private final int limit, total;
    private int inFlight, completed, succeeded, failed, sources; private boolean cancelled, keepLoaded;
    public QueueState(List<String> urls, int limit){
      if(limit<1||limit>8) throw new IllegalArgumentException("concurrency 1..8");
      pending=new ArrayDeque<>(urls); this.limit=limit; total=urls.size();
    }
    public synchronized String takeNext(){ if(cancelled||inFlight>=limit||pending.isEmpty()) return null; inFlight++; return pending.removeFirst(); }
    public synchronized void complete(boolean ok, int count){ if(inFlight>0) inFlight--; completed++; if(ok) succeeded++; else failed++; sources+=Math.max(0,count); }
    public synchronized void cancel(boolean keep){ cancelled=true; keepLoaded=keep; pending.clear(); }
    public synchronized boolean shouldKeepLoaded(){ return keepLoaded; }
    public synchronized boolean isCancelled(){ return cancelled; }
    public synchronized boolean isDone(){ return (pending.isEmpty()&&inFlight==0) || (cancelled&&inFlight==0); }
    public synchronized FetchProgress progress(){ return new FetchProgress(total, completed, succeeded, failed, sources, !isDone(), cancelled); }
  }
  // Network JSON is requested directly from the user-provided URL.
  private final Set<HttpURLConnection> active=Collections.synchronizedSet(new HashSet<>());
  private volatile QueueState state; private volatile FetchListener listener; private final AtomicInteger workers=new AtomicInteger();
  private final int maxBytes = 32 * 1024 * 1024;
  public synchronized void start(List<String> urls, int concurrency, FetchListener l){
    if(state!=null && !state.isDone()) throw new IllegalStateException("task running");
    state=new QueueState(urls, concurrency); listener=l; listener.onProgress(state.progress());
    workers.set(concurrency);
    for(int i=0;i<concurrency;i++) new Thread(this::work, "source-fetch-"+i).start();
  }
  public void cancel(boolean keep){ QueueState s=state; if(s==null) return; s.cancel(keep); synchronized(active){ for(HttpURLConnection c:new ArrayList<>(active)) c.disconnect(); } }
  private void work(){
    try{
      while(true){
        String url=state.takeNext(); if(url==null) break;
        try{
          String body=fetch(url);
          int count=estimateCount(body);
          state.complete(true, count);
          listener.onItem(url, body);
        }catch(Exception e){
          state.complete(false, 0);
          String msg = e.getMessage();
          if(msg==null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
          listener.onFailure(url, e.getClass().getSimpleName()+":"+msg);
        }
        listener.onProgress(state.progress());
      }
    } finally {
      if(workers.decrementAndGet()==0) listener.onFinished(state.isCancelled(), state.shouldKeepLoaded());
    }
  }
  private int estimateCount(String body){
    try{
      SourceParser.ParseResult p=SourceParser.parseArray(body,0);
      return p.getRecords().size();
    }catch(Exception e){ return 0; }
  }
  private String fetch(String url) throws Exception {
    return doFetch(url);
  }
  private String doFetch(String url) throws Exception {
    HttpURLConnection c = open(url);
    active.add(c);
    try{
      int code = c.getResponseCode();
      // some stacks surface 3xx even with follow-redirects; follow a few hops manually
      int hops = 0;
      while (code >= 300 && code < 400 && hops < 5) {
        String loc = c.getHeaderField("Location");
        if (loc == null || loc.trim().isEmpty()) break;
        URL next = new URL(c.getURL(), loc);
        active.remove(c); c.disconnect();
        c = open(next.toString());
        active.add(c);
        code = c.getResponseCode();
        hops++;
      }
      if(code < 200 || code >= 300) throw new IOException("HTTP "+code);
      InputStream raw = c.getErrorStream()!=null && code>=400 ? c.getErrorStream() : c.getInputStream();
      if (raw == null) throw new IOException("empty body");
      InputStream in = wrapMaybeGzip(raw, c.getContentEncoding());
      try{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] b=new byte[8192]; int n; int total=0;
        while((n=in.read(b))>=0){ total+=n; if(total>maxBytes) throw new IOException("response too large ("+total+")"); out.write(b,0,n); }
        if(total==0) throw new IOException("empty body");
        return out.toString("UTF-8");
      } finally { in.close(); }
    } finally { active.remove(c); c.disconnect(); }
  }
  private HttpURLConnection open(String url) throws Exception {
    HttpURLConnection c=(HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(20000);
    c.setReadTimeout(90000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", "YueduSourceDedupe/3.0");
    c.setRequestProperty("Accept", "application/json,text/plain,*/*");
    // 不主动声明 gzip：对齐 2.3.10，避免 Android 透明解压与二次 GZIP 冲突
    return c;
  }
  private InputStream wrapMaybeGzip(InputStream raw, String enc) throws IOException {
    boolean claimGzip = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
    if (!claimGzip) return raw;
    // 仅当确实是 gzip 魔数时再包一层，防止已透明解压后再次解压失败
    PushbackInputStream pb = new PushbackInputStream(raw, 2);
    int b1 = pb.read(); int b2 = pb.read();
    if (b1 < 0) return pb;
    if (b2 < 0) { pb.unread(b1); return pb; }
    pb.unread(new byte[]{(byte)b1,(byte)b2});
    if (b1 == 0x1f && b2 == 0x8b) return new GZIPInputStream(pb);
    return pb;
  }
}
