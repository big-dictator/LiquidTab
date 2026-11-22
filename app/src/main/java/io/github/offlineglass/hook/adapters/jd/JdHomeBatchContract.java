package io.github.offlineglass.hook.adapters.jd;

import java.lang.reflect.*;
import java.util.*;

/** Home viewport accounting -> native manager -> native refresh config contract. */
public final class JdHomeBatchContract {
    public record Binding(Method completed, Method config) {}
    private static boolean instance(Method method) {return !Modifier.isStatic(method.getModifiers());}
    private static Method unique(Class<?> owner, String name, Class<?> result, boolean staticRole, Class<?>... parameters) {
        var matches=Arrays.stream(owner.getDeclaredMethods()).filter(m -> m.getName().equals(name) &&
                m.getReturnType()==result && Arrays.equals(m.getParameterTypes(),parameters) &&
                Modifier.isStatic(m.getModifiers())==staticRole).toList();
        if(matches.size()!=1)throw new IllegalStateException("Home role "+name+" candidates="+matches.size());
        return matches.getFirst();
    }
    public static Binding resolve(Class<?> home, Class<?> group, Class<?> manager, Class<?> feature, Class<?> json) {
        if(!group.isAssignableFrom(home))throw new IllegalStateException("Home list is not a ViewGroup");
        var counters=Arrays.stream(home.getDeclaredMethods()).filter(m -> instance(m) &&
                m.getReturnType()==int.class && Arrays.equals(m.getParameterTypes(),new Class<?>[]{double.class})).toList();
        if(counters.size()!=1)throw new IllegalStateException("Home viewport-count candidates="+counters.size());
        int managerFields=0,depth=0;
        for(Class<?> type=home; type!=null && depth++<16;type=type.getSuperclass()) {
            for(Field field:type.getDeclaredFields()) if(!Modifier.isStatic(field.getModifiers()) && field.getType()==manager)managerFields++;
        }
        if(managerFields!=1)throw new IllegalStateException("Home native manager candidates="+managerFields);
        unique(manager,"setHomeColdStartScreenCount",void.class,false,int.class);
        return new Binding(unique(home,"onRecommendOnePageRender",void.class,false),
                unique(feature,"getRecommendDelayRenderConfig",json,true));
    }
}
