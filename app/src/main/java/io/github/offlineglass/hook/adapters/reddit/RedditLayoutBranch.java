package io.github.offlineglass.hook.adapters.reddit;

import java.lang.reflect.*;
import java.util.*;

/** Select only unary layout ancestors: these own native bar background/alpha,
 * never a sibling feed. Child vectors (layout order and draw order) must agree. */
public final class RedditLayoutBranch {
    private static final ClassValue<List<Method>> VECTORS=new ClassValue<>() {
        protected List<Method> computeValue(Class<?> type) {
            List<Method> found=new ArrayList<>();
            for(Method m:type.getMethods())if(!Modifier.isStatic(m.getModifiers()) && m.getParameterCount()==0 &&
                RandomAccess.class.isAssignableFrom(m.getReturnType()) &&
                RedditReflection.field(m.getReturnType(), f->f.getType()==Object[].class)!=null &&
                RedditReflection.field(m.getReturnType(), f->f.getType()==int.class)!=null) { m.setAccessible(true);found.add(m); }
            return found;
        }
    };
    public static Object resolve(Object bar,Object root) throws ReflectiveOperationException {
        Class<?> type=root.getClass();
        Method parent=RedditReflection.method(type,m->m.getParameterCount()==0 && m.getReturnType()==type);
        if(parent==null)return bar;
        Object current=bar;
        Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(int depth=0;depth<12 && seen.add(current);depth++) {
            Object outer=parent.invoke(current);
            if(outer==null || outer==root || !type.isInstance(outer))break;
            List<Method> vectors=VECTORS.get(type);
            if(vectors.isEmpty())break;
            boolean unary=true;
            for(Method getter:vectors) {
                Object vector=getter.invoke(outer);
                if(vector==null) { unary=false;break; }
                Object[] values=(Object[])RedditReflection.field(vector.getClass(),f->f.getType()==Object[].class).get(vector);
                int size=RedditReflection.field(vector.getClass(),f->f.getType()==int.class).getInt(vector);
                if(size!=1 || values==null || values.length<1 || values[0]!=current) { unary=false;break; }
            }
            if(!unary)break;
            current=outer;
        }
        return current;
    }
    private RedditLayoutBranch() {}
}
