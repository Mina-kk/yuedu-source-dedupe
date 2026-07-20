package com.mina.yuedu.core;
import com.mina.yuedu.model.*;
public final class ResultSummary {
 private ResultSummary(){}
 public static String format(DedupeMode mode,int local,int network,DedupeResult r,boolean partial){return (partial?"结果不完整\n":"")+"当前去重模式："+mode.label()+"\n本地书源："+local+" · 网络书源："+network+"\n原始 "+r.getOriginalCount()+"   重复 "+r.getDuplicateCount()+"   有效 "+r.getRetained().size()+"   错误 "+r.getInvalid().size();}
}
