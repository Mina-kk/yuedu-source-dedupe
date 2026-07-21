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
    private long lastErrorToastAt;

    public YckWebClient(Listener l) {
        listener = l;
    }

    public void allowNextJson() {
        allowNextJson = true;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        return route(r.getUrl().toString());
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, String u) {
        return route(u);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        // Only hard-block known ad hosts. Do not block useful CDNs/static assets.
        if (!YckUrlPolicy.safeResource(url)) {
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        }
        return null;
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        if (YckUrlPolicy.allowed(url)) listener.onPageFinished(url);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        // Only surface main-frame failures, and debounce repeated toasts.
        if (request == null || !request.isForMainFrame()) return;
        long now = System.currentTimeMillis();
        if (now - lastErrorToastAt < 2500) return;
        lastErrorToastAt = now;
        listener.onLoadError(request.getUrl().toString());
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        if (request == null || !request.isForMainFrame()) return;
        int code = errorResponse == null ? 0 : errorResponse.getStatusCode();
        if (code >= 400) {
            long now = System.currentTimeMillis();
            if (now - lastErrorToastAt < 2500) return;
            lastErrorToastAt = now;
            listener.onLoadError(request.getUrl().toString());
        }
    }

    private boolean route(String u) {
        if (YckUrlPolicy.json(u)) {
            if (allowNextJson) {
                allowNextJson = false;
                return false;
            }
            listener.onJsonLink(u);
            return true;
        }
        if (YckUrlPolicy.allowed(u)) return false;
        listener.onExternal(u);
        return true;
    }
}
