package com.mina.yuedu.model;
import java.util.*;
public final class DuplicateGroup {
 private final String key,reason; private final SourceRecord kept; private final List<SourceRecord> removed;
 public DuplicateGroup(String key,String reason,SourceRecord kept,List<SourceRecord> removed){this.key=key;this.reason=reason;this.kept=kept;this.removed=Collections.unmodifiableList(new ArrayList<>(removed));}
 public String getKey(){return key;} public String getReason(){return reason;} public SourceRecord getKept(){return kept;} public List<SourceRecord> getRemoved(){return removed;}
}
