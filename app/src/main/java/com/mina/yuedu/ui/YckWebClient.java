package com.mina.yuedu.ui;
import android.webkit.*;
import com.mina.yuedu.network.YckUrlPolicy;
import java.io.ByteArrayInputStream;
public final class YckWebClient extends WebViewClient {
  public interface Listener {
    void onJsonLink(String url);
    void onExternal(String url);
    void onLoadError(String url);
    void onPageFinished(String url);
  }
  private final Listener listener;
  private boolean allowNextJson;
  public YckWebClient(Listener listener){ this.listener = listener; }
  public void allowNextJson(){ allowNextJson = true; }
  @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
    return route(request.getUrl() == null ? null : request.getUrl().toString());
  }
  @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
    return route(url);
  }
  @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
    String u = request.getUrl() == null ? "" : request.getUrl().toString();
    if (!YckUrlPolicy.safeResource(u)) {
      return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }
    return null;
  }
  @Override public void onPageFinished(WebView view, String url) {
    if (YckUrlPolicy.allowed(url)) listener.onPageFinished(url);
  }
  @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
    if (request.isForMainFrame()) listener.onLoadError(request.getUrl() == null ? "" : request.getUrl().toString());
  }
  @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
    if (request.isForMainFrame()) listener.onLoadError(request.getUrl() == null ? "" : request.getUrl().toString());
  }
  private boolean route(String u) {
    if (u == null) return true;
    if (YckUrlPolicy.json(u)) {
      if (allowNextJson) { allowNextJson = false; return false; }
      listener.onJsonLink(u);
      return true;
    }
    if (YckUrlPolicy.allowed(u)) return false;
    listener.onExternal(u);
    return true;
  }
}
