package com.mina.yuedu.model;
import java.util.*;
public final class DedupeResult {
 private final int originalCount; private final List<SourceRecord> retained; private final List<DuplicateGroup> groups; private final List<InvalidSource> invalid;
 public DedupeResult(int n,List<SourceRecord> r,List<DuplicateGroup> g,List<InvalidSource> i){originalCount=n;retained=Collections.unmodifiableList(new ArrayList<>(r));groups=Collections.unmodifiableList(new ArrayList<>(g));invalid=Collections.unmodifiableList(new ArrayList<>(i));}
 public int getOriginalCount(){return originalCount;} public List<SourceRecord> getRetained(){return retained;} public List<DuplicateGroup> getDuplicateGroups(){return groups;} public List<InvalidSource> getInvalid(){return invalid;}
 public int getDuplicateCount(){int n=0;for(DuplicateGroup g:groups)n+=g.getRemoved().size();return n;}
}
