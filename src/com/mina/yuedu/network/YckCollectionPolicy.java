package com.mina.yuedu.network;
import java.util.Set;
public final class YckCollectionPolicy {
 private YckCollectionPolicy(){}
 public static String collect(String url,Set<String> existing){
  if(!YckUrlPolicy.collectable(url))return "invalid";
  return existing.add(url.trim())?"added":"duplicate";
 }
}
