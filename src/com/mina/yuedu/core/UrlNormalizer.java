package com.mina.yuedu.core;
import com.mina.yuedu.model.DedupeMode;
import java.net.*;import java.util.*;
public final class UrlNormalizer {
 private UrlNormalizer(){}
 public static String completeKey(String raw)throws URISyntaxException{return canonical(raw,false);}
 public static String hostKey(String raw)throws URISyntaxException{return canonical(raw,true);}
 public static String key(String raw,DedupeMode mode)throws URISyntaxException{return mode==DedupeMode.AGGRESSIVE?hostKey(raw):completeKey(raw);}
 private static String canonical(String raw,boolean hostOnly)throws URISyntaxException{URI u=new URI(raw.trim());if(u.getScheme()==null||u.getHost()==null)throw new URISyntaxException(raw,"missing scheme or host");String scheme=u.getScheme().toLowerCase(Locale.ROOT),host=u.getHost().toLowerCase(Locale.ROOT);if(hostOnly)host=host.replaceFirst("^www\\.","");int port=u.getPort();boolean def=(scheme.equals("http")&&port==80)||(scheme.equals("https")&&port==443);String hp=host+(port>=0&&!def?":"+port:"");if(hostOnly)return hp;String path=u.getRawPath();if(path==null||path.isEmpty())path="/";String q=u.getRawQuery();return scheme+"://"+hp+path+(q==null?"":"?"+q);}
}
