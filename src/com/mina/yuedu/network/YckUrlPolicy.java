package com.mina.yuedu.network;
import java.net.*;import java.util.*;
public final class YckUrlPolicy {
  private YckUrlPolicy(){}
  public static boolean allowed(String raw){
    try{
      String h=new URL(raw).getHost().toLowerCase(Locale.ROOT);
      return hostOk(h);
    }catch(Exception e){return false;}
  }
  private static boolean hostOk(String h){
    return eqOrSub(h,"yckceo.vip")
        || eqOrSub(h,"yckceo.com")
        || eqOrSub(h,"yck2026.fun")
        || eqOrSub(h,"yck2026.top"); // 旧备用域名保留白名单，避免历史链接被拦
  }
  private static boolean eqOrSub(String h,String root){
    return h.equals(root) || h.endsWith("."+root);
  }
  public static boolean safeResource(String raw){return allowed(raw);}
  public static boolean collectable(String raw){
    try{
      URL u=new URL(raw); String p=u.getPath();
      return allowed(raw)&&(p.toLowerCase(Locale.ROOT).endsWith(".json")||p.matches("^/d/[^/?#]+$"));
    }catch(Exception e){return false;}
  }
  public static boolean json(String raw){
    try{return allowed(raw)&&new URL(raw).getPath().toLowerCase(Locale.ROOT).endsWith(".json");}
    catch(Exception e){return false;}
  }
}
