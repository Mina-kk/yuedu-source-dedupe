package com.mina.yuedu.ui;
import android.webkit.*;import com.mina.yuedu.network.YckUrlPolicy;import java.io.ByteArrayInputStream;
public final class YckWebClient extends WebViewClient {
 public interface Listener{void onJsonLink(String url);void onExternal(String url);void onLoadError(String url);void onPageFinished(String url);}
 private final Listener listener;private boolean allowNextJson;public YckWebClient(Listener l){listener=l;}public void allowNextJson(){allowNextJson=true;}
 @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return route(r.getUrl().toString());}@Override public boolean shouldOverrideUrlLoading(WebView v,String u){return route(u);}
 @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){if(!YckUrlPolicy.safeResource(request.getUrl().toString()))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}
 @Override public void onPageFinished(WebView view,String url){if(YckUrlPolicy.allowed(url))listener.onPageFinished(url);}
 @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())listener.onLoadError(request.getUrl().toString());}
 private boolean route(String u){if(YckUrlPolicy.json(u)){if(allowNextJson){allowNextJson=false;return false;}listener.onJsonLink(u);return true;}if(YckUrlPolicy.allowed(u))return false;listener.onExternal(u);return true;}
}
