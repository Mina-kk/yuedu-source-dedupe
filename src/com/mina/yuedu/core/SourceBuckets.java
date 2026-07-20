package com.mina.yuedu.core;
import com.mina.yuedu.model.SourceRecord;
import java.util.*;
public final class SourceBuckets {
 private final List<SourceRecord> local=new ArrayList<>(),network=new ArrayList<>();
 public void addLocal(List<SourceRecord> records){local.addAll(records);}
 public void replaceNetwork(List<SourceRecord> records){network.clear();network.addAll(records);} public void addNetwork(List<SourceRecord> records){network.addAll(records);} public boolean isEmpty(){return local.isEmpty()&&network.isEmpty();}
 public int localCount(){return local.size();} public int networkCount(){return network.size();} public List<SourceRecord> all(){List<SourceRecord> out=new ArrayList<>(local.size()+network.size());out.addAll(local);out.addAll(network);return out;}
 public void clearAll(){local.clear();network.clear();}
}
