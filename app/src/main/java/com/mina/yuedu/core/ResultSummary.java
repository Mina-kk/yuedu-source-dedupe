package com.mina.yuedu.core;
import com.mina.yuedu.model.*;
public final class ResultSummary {
  private ResultSummary(){}
  public static String format(DedupeMode mode,int local,int network,DedupeResult r,boolean partial){
    return (partial?"结果不完整 · ":"")+mode.label()+" · 本地 "+local+" · 网络 "+network;
  }
}
