package io.github.offlineglass.hook.adapters.jd;

import java.lang.reflect.*;
import java.util.*;

/** The actual Message tab's model+draw-type UI entry; private names may change. */
public final class JdMessageContentContract {
    public static Method resolve(Class<?> page,Class<?> tab,Class<?> model) {
        if(!tab.isAssignableFrom(page))throw new IllegalStateException("not a native tab");
        var matches=Arrays.stream(page.getDeclaredMethods()).filter(m ->
            !Modifier.isStatic(m.getModifiers()) && !m.isBridge() && !m.isSynthetic() && m.getReturnType()==void.class &&
            Arrays.equals(m.getParameterTypes(),new Class<?>[]{model,String.class})).toList();
        if(matches.size()!=1)throw new IllegalStateException("message model/draw-type entry candidates="+matches.size());
        return matches.getFirst();
    }
}
