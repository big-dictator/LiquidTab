package io.github.offlineglass.hook.adapters.taobao;
import java.lang.reflect.*;import java.util.*;
/** Resolves model roles using the documented ability input, independent of field symbols. */
public record TaobaoCartAbilityContract(Field instance,Field method,Field data){
 public static TaobaoCartAbilityContract resolve(Class<?> type)throws Exception{
  String marker="__liquidtab_binding_instance__";Map<String,Object> payload=new LinkedHashMap<>();payload.put("__liquidtabBindingProbe",true);
  Object probe=type.getConstructor(Map.class).newInstance(Map.of("instanceID",marker,"method","saveInstanceState","params",payload));
  List<Field> ids=new ArrayList<>(),methods=new ArrayList<>(),maps=new ArrayList<>();
  for(Field field:type.getFields()){if(Modifier.isStatic(field.getModifiers()))continue;Object value=field.get(probe);
   if(field.getType()==String.class){if(marker.equals(value))ids.add(field);if("saveInstanceState".equals(value))methods.add(field);}
   if(Map.class.isAssignableFrom(field.getType())&&value==payload)maps.add(field);
  }
  if(ids.size()!=1||methods.size()!=1||maps.size()!=1)throw new IllegalStateException("Cart ability role ambiguity "+ids.size()+":"+methods.size()+":"+maps.size());
  return new TaobaoCartAbilityContract(ids.get(0),methods.get(0),maps.get(0));
 }
}
