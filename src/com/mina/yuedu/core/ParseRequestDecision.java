package com.mina.yuedu.core;
public final class ParseRequestDecision {private ParseRequestDecision(){}public static boolean shouldRun(int localCount,int networkUrlCount){return localCount>0||networkUrlCount>0;}}
