package com.mina.yuedu.network;
import java.net.*;import java.util.*;
public final class YckUrlPolicy {private YckUrlPolicy(){}public static boolean allowed(String raw){try{String h=new URL(raw).getHost().toLowerCase(Locale.ROOT);return h.equals("yckceo.vip")||h.endsWith(".yckceo.vip")||h.equals("yckceo.com")||h.endsWith(".yckceo.com")||h.equals("yck2026.top")||h.endsWith(".yck2026.top");}catch(Exception e){return false;}}public static boolean safeResource(String raw){return allowed(raw);}
 public static boolean collectable(String raw){try{URL u=new URL(raw);String p=u.getPath();return allowed(raw)&&(p.toLowerCase(Locale.ROOT).endsWith(".json")||p.matches("^/d/[^/?#]+$"));}catch(Exception e){return false;}}
 public static boolean json(String raw){try{return allowed(raw)&&new URL(raw).getPath().toLowerCase(Locale.ROOT).endsWith(".json");}catch(Exception e){return false;}}}
