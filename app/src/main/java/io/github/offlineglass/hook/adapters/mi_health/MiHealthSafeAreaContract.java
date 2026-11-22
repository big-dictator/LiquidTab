package io.github.offlineglass.hook.adapters.mi_health;
import java.lang.reflect.*;
import java.util.*;
/** Structural listener contract, independent of minified record/member names. */
public final class MiHealthSafeAreaContract {
    public record Binding(Method setter, Method callback) {}
    public static Binding resolve(Class<?> provider) {
        List<Binding> matches = new ArrayList<>();
        for (Method setter : provider.getDeclaredMethods()) {
            if (!setter.getName().equals("setOnInsetsChangeListener") || setter.getParameterCount()!=1) continue;
            Class<?> listener=setter.getParameterTypes()[0];
            if (!listener.isInterface()) continue;
            for(Method callback:listener.getDeclaredMethods()) {
                Class<?>[] p=callback.getParameterTypes();
                if(callback.getReturnType()==void.class && p.length==3 && p[0]==provider && record(p[1]) && record(p[2]))
                    matches.add(new Binding(setter,callback));
            }
        }
        if(matches.size()!=1) throw new IllegalStateException("Safe-area contract candidates="+matches.size());
        return matches.get(0);
    }
    private static boolean record(Class<?> type) {
        int count=0;
        for(Field f:type.getDeclaredFields()) if(!Modifier.isStatic(f.getModifiers()) && f.getType()==float.class) count++;
        return count==4;
    }
}
