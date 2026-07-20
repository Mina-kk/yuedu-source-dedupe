package com.mina.yuedu.core;
public final class NameCleaner {
 private NameCleaner(){}
 public static String clean(String s){if(s==null)return null;StringBuilder b=new StringBuilder();for(int i=0;i<s.length();){int cp=s.codePointAt(i);i+=Character.charCount(cp);if(cp==0x200B||cp==0x200C||cp==0x200D||cp==0xFEFF||cp>=0x1F000&&cp<=0x1FAFF||cp>=0x2600&&cp<=0x27BF)continue;b.appendCodePoint(cp);}return b.toString().replaceAll("\\s+"," ").trim();}
}
