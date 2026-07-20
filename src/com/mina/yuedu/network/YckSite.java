package com.mina.yuedu.network;
public enum YckSite {
 MAIN("main","主站","https://www.yckceo.com/yuedu/shuyuans/index.html"),
 BACKUP("backup","备用","https://www.yck2026.top/");
 private final String value,label,url;
 YckSite(String value,String label,String url){this.value=value;this.label=label;this.url=url;}
 public String preference(){return value;} public String label(){return label;} public String entryUrl(){return url;}
 public static YckSite fromPreference(String value){for(YckSite s:values())if(s.value.equals(value))return s;return MAIN;}
}
