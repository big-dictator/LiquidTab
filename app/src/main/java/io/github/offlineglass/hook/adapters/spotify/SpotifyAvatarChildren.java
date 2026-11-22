package io.github.offlineglass.hook.adapters.spotify;

import java.lang.reflect.*;
import java.util.*;

/** Verified folded-child holder/vector contract, independent of internal names. */
public final class SpotifyAvatarChildren {
    private static final ClassValue<List<Field>> FIELDS=new ClassValue<List<Field>>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            List<Field> fields=new ArrayList<>();
            for(Class<?> t=type;t!=null && t!=Object.class && !t.getName().startsWith("java.");t=t.getSuperclass())
                for(Field f:t.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers()))try {
                    f.setAccessible(true);fields.add(f);
                } catch(RuntimeException ignored) { }
            return fields;
        }
    };
    private static Object get(Field field,Object target) {
        try { return field.get(target); } catch(ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
    public static List<Object> read(Object node,Class<?> nodeType,List<?> holders) {
        Set<Object> result=Collections.newSetFromMap(new IdentityHashMap<>());
        int examined=0;
        for(Object holder:holders) {
            if(holder==null || nodeType.isInstance(holder))continue;
            List<Object> candidates=new ArrayList<>();candidates.add(holder);
            for(Field f:FIELDS.get(holder.getClass()))if(!f.getType().isPrimitive()) {
                Object value=get(f,holder);
                if(value!=null && !nodeType.isInstance(value))candidates.add(value);
            }
            for(Object candidate:candidates) {
                if(++examined>128)return new ArrayList<>(result);
                List<Field> arrays=new ArrayList<>(),counts=new ArrayList<>();
                for(Field f:FIELDS.get(candidate.getClass())) {
                    if(f.getType().isArray())arrays.add(f);
                    if(f.getType()==int.class)counts.add(f);
                }
                if(arrays.size()!=1 || counts.size()!=1)continue;
                Object raw=get(arrays.get(0),candidate),size=get(counts.get(0),candidate);
                if(!(raw instanceof Object[]) || !(size instanceof Integer))continue;
                Object[] children=(Object[])raw;int count=(Integer)size;
                if(count<0 || count>children.length || count>32)continue;
                // Native MutableVector grows a typed initial array into Object[].
                // Validate every live element and its parent, not the array's
                // incidental component type. Never accept a partial foreign vector.
                List<Object> verified=new ArrayList<>();boolean valid=true;
                for(int i=0;i<count;i++) {
                    Object child=children[i];boolean linked=false;
                    if(child!=null && nodeType.isInstance(child))
                        for(Field f:FIELDS.get(child.getClass()))if(f.getType()==nodeType && get(f,child)==node) { linked=true;break; }
                    if(!linked) { valid=false;break; }
                    verified.add(child);
                }
                if(valid)result.addAll(verified);
            }
        }
        return new ArrayList<>(result);
    }
    private SpotifyAvatarChildren() { }
}
