package com.mina.yuedu.network;
public interface FetchListener {
 void onProgress(FetchProgress p); void onItem(String url,String body); void onFailure(String url,String message); void onFinished(boolean cancelled,boolean keepLoaded);
}
