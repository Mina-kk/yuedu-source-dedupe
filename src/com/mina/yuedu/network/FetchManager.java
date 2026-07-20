package com.mina.yuedu.network;
import java.io.*;import java.net.*;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
public final class FetchManager {
 public static final class QueueState {
  private final Deque<String> pending;private final int limit,total;private int inFlight,completed,succeeded,failed,sources;private boolean cancelled,keepLoaded;
  public QueueState(List<String> urls,int limit){if(limit<1||limit>5)throw new IllegalArgumentException("concurrency 1..5");pending=new ArrayDeque<>(urls);this.limit=limit;total=urls.size();}
  public synchronized String takeNext(){if(cancelled||inFlight>=limit||pending.isEmpty())return null;inFlight++;return pending.removeFirst();}
  public synchronized void complete(boolean ok,int count){if(inFlight>0)inFlight--;completed++;if(ok)succeeded++;else failed++;sources+=Math.max(0,count);}
  public synchronized void cancel(boolean keep){cancelled=true;keepLoaded=keep;pending.clear();}
  public synchronized int getInFlight(){return inFlight;}public synchronized boolean shouldKeepLoaded(){return keepLoaded;}public synchronized boolean isCancelled(){return cancelled;}public synchronized boolean isDone(){return (pending.isEmpty()&&inFlight==0)||cancelled&&inFlight==0;}
  public synchronized FetchProgress progress(){return new FetchProgress(total,completed,succeeded,failed,sources,!isDone(),cancelled);}
 }
 private final Set<HttpURLConnection> active=Collections.synchronizedSet(new HashSet<HttpURLConnection>());private volatile QueueState state;private volatile FetchListener listener;private final AtomicInteger workers=new AtomicInteger();
 public synchronized void start(List<String> urls,int concurrency,FetchListener l){if(state!=null&&!state.isDone())throw new IllegalStateException("task running");state=new QueueState(urls,concurrency);listener=l;listener.onProgress(state.progress());workers.set(concurrency);for(int i=0;i<concurrency;i++)new Thread(this::work,"source-fetch-"+i).start();}
 public void cancel(boolean keep){QueueState s=state;if(s==null)return;s.cancel(keep);synchronized(active){for(HttpURLConnection c:new ArrayList<>(active))c.disconnect();}}
 private void work(){try{while(true){String url=state.takeNext();if(url==null)break;try{String body=fetch(url);state.complete(true,0);listener.onItem(url,body);}catch(Exception e){state.complete(false,0);listener.onFailure(url,e.getClass().getSimpleName()+":"+e.getMessage());}listener.onProgress(state.progress());}}finally{if(workers.decrementAndGet()==0)listener.onFinished(state.isCancelled(),state.shouldKeepLoaded());}}
 private String fetch(String url)throws Exception{return doFetch(url,Proxy.NO_PROXY);}
 private String doFetch(String url,Proxy proxy)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(proxy);active.add(c);try{c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(true);c.setRequestProperty("User-Agent","YueduSourceDedupe/2.0");InputStream in=c.getInputStream();try{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);return out.toString("UTF-8");}finally{in.close();}}finally{active.remove(c);c.disconnect();}}
}
