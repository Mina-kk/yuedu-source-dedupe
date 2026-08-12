package com.mina.yuedu.core;
public final class ParseRequestDecision {
  private ParseRequestDecision(){}
  public static boolean shouldRun(int localCount, int urlCount){ return localCount>0 || urlCount>0; }
}
