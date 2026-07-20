package com.mina.yuedu.model;
public final class InvalidSource {
 public enum Kind { NOT_OBJECT,MISSING_URL,EMPTY_URL,INVALID_URL,NOT_JSON_ARRAY,NETWORK_FAILURE,NO_YCK_JSON }
 private final Kind kind; private final String detail;
 public InvalidSource(Kind kind,String detail){this.kind=kind;this.detail=detail;}
 public Kind getKind(){return kind;} public String getDetail(){return detail;}
}
