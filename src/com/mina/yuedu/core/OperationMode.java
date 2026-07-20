package com.mina.yuedu.core;
import com.mina.yuedu.model.DedupeMode;
public final class OperationMode {
 private DedupeMode selected,result;
 public OperationMode(DedupeMode initial){selected=initial;result=initial;}
 public void select(DedupeMode mode){selected=mode;}
 public void start(){result=selected;}
 public DedupeMode selectedMode(){return selected;}
 public DedupeMode resultMode(){return result;}
}
