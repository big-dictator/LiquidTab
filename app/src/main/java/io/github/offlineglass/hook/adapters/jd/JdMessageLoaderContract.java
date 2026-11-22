package io.github.offlineglass.hook.adapters.jd;

import java.lang.reflect.*;
import java.util.*;

/** Locate the owning bundle and its back-reference by roles, never SDK private symbols. */
public final class JdMessageLoaderContract {
    public record Binding(Method bundle, Method location, Method owner, Method load) {}
    private static boolean zeroInstance(Method m) {
        return m.getParameterCount()==0 && !Modifier.isStatic(m.getModifiers()) && !m.isBridge();
    }
    public static Binding resolve(Class<?> loader, Class<?> dexBase) {
        if(loader==dexBase || !dexBase.isAssignableFrom(loader))throw new IllegalStateException("not a bundle DEX loader");
        List<Binding> candidates=new ArrayList<>();
        for(Method getter:loader.getMethods()) {
            if(!zeroInstance(getter) || getter.getReturnType().isPrimitive())continue;
            Class<?> bundle=getter.getReturnType();
            Method location,start;
            try {location=bundle.getMethod("getLocation");start=bundle.getMethod("start");}
            catch(NoSuchMethodException unrelated){continue;}
            if(!zeroInstance(location) || location.getReturnType()!=String.class || !zeroInstance(start) || start.getReturnType()!=void.class)continue;
            var owners=Arrays.stream(bundle.getMethods()).filter(m -> zeroInstance(m) &&
                    ClassLoader.class.isAssignableFrom(m.getReturnType())).toList();
            if(owners.size()!=1)continue;
            getter.setAccessible(true);location.setAccessible(true);owners.getFirst().setAccessible(true);
            candidates.add(new Binding(getter,location,owners.getFirst(),null));
        }
        if(candidates.size()!=1)throw new IllegalStateException("bundle-owner role candidates="+candidates.size());
        Method load=null;int depth=0;
        for(Class<?> type=loader;type!=null && type!=dexBase && depth++<16;type=type.getSuperclass()) {
            var methods=Arrays.stream(type.getDeclaredMethods()).filter(m -> !Modifier.isStatic(m.getModifiers()) &&
                    m.getName().equals("loadClass") && m.getReturnType()==Class.class &&
                    Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,boolean.class})).toList();
            if(methods.size()>1)throw new IllegalStateException("ambiguous bundle load override");
            if(methods.size()==1){load=methods.getFirst();break;}
        }
        if(load==null)throw new IllegalStateException("bundle load override missing");
        var matched=candidates.getFirst();
        return new Binding(matched.bundle,matched.location,matched.owner,load);
    }
}
