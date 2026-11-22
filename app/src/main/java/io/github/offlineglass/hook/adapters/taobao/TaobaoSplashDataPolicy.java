package io.github.offlineglass.hook.adapters.taobao;
import java.util.*;
/** Boot-only section schema verified from native content extraction, not a global JSON filter. */
public final class TaobaoSplashDataPolicy{
 private static Object get(Object value,String key){return value instanceof Map<?,?> map?map.get(key):null;}
 public static boolean isVisualSplash(Object section,Set<String> types){
  Object content=get(get(get(get(section,"subSection"),"splash"),"item"),"0");
  Object type=get(get(content,"content"),"bizType");return type instanceof String&&types.contains(type);
 }
 public static List<?> withoutVisualSplash(List<?> source,Set<String> types){
  if(source.size()>2048)throw new IllegalArgumentException("Splash section count");
  ArrayList<Object> removed=new ArrayList<>();for(Object item:source)if(isVisualSplash(item,types))removed.add(item);
  if(!removed.isEmpty())source.removeAll(removed);return source;
 }
}
