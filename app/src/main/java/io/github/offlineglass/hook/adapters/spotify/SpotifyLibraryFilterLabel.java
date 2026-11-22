package io.github.offlineglass.hook.adapters.spotify;
import java.util.*;
/** Text and descriptions belong to the descendants of the same native action. */
public final class SpotifyLibraryFilterLabel {
 public static String resolve(List<String> texts,List<String> descriptions,Map<String,String> labels) {
  Set<String> actual=new LinkedHashSet<>();for(String s:texts)if(!s.isBlank())actual.add(s);
  if(actual.size()==1)return actual.iterator().next();if(actual.size()>1)return null;
  Set<String> matched=new LinkedHashSet<>();for(String s:descriptions)if(labels.containsKey(s))matched.add(labels.get(s));
  if(matched.size()==1)return matched.iterator().next();if(matched.size()>1)return null;
  Set<String> raw=new LinkedHashSet<>();for(String s:descriptions)if(!s.isBlank())raw.add(s);
  return raw.size()==1?raw.iterator().next():null;
 }
 private SpotifyLibraryFilterLabel(){}
}
