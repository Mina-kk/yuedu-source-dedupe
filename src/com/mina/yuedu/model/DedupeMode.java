package com.mina.yuedu.model;
public enum DedupeMode {
 STANDARD("标准", "仅规范化后的完整书源 URL 相同才去重；与阅读书源身份一致，保留同域不同路径。"),
 STRICT("严格", "仅规范化后的完整书源 URL 相同才去重；不会按域名删除不同路径书源。"),
 AGGRESSIVE("激进", "同一域名只保留一个书源；去重最多，也最可能误删。 ");
 private final String label,description;
 DedupeMode(String label,String description){this.label=label;this.description=description;}
 public String label(){return label;} public String description(){return description;}
}
