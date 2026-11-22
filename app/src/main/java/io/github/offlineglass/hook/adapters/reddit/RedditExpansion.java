package io.github.offlineglass.hook.adapters.reddit;

import java.lang.reflect.*;
import java.util.*;

/** Animatable -> AnimationState (value, two frame timestamps, running flag).
 * The current APK uses this same state for native bar placement and alpha.
 * Handles are instance-bound and never reused across BottomNavScreen rebuilds.
 */
public final class RedditExpansion {
    private final Object state;
    private final Method getter;
    private RedditExpansion(Object state, Method getter) { this.state=state;this.getter=getter; }
    public float value() throws ReflectiveOperationException {
        Object raw=getter.invoke(state);
        if(!(raw instanceof Float))throw new IllegalStateException("native expansion is not Float");
        float f=(Float)raw;
        if(!Float.isFinite(f) || f<0 || f>1)throw new IllegalStateException("native expansion outside [0,1]");
        return f;
    }
    public static RedditExpansion stateIn(Object animator) throws ReflectiveOperationException {
        List<RedditExpansion> found=new ArrayList<>();
        for(Field field:animator.getClass().getFields()) {
            if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())continue;
            Object value=field.get(animator);
            if(value==null)continue;
            Field[] fields=value.getClass().getFields();
            long timestamps=Arrays.stream(fields).filter(f->!Modifier.isStatic(f.getModifiers()) && f.getType()==long.class).count();
            long running=Arrays.stream(fields).filter(f->!Modifier.isStatic(f.getModifiers()) && f.getType()==boolean.class).count();
            if(timestamps!=2 || running!=1)continue;
            Method getter=RedditReflection.method(value.getClass(),m->m.getName().equals("getValue") && m.getParameterCount()==0);
            if(getter==null)continue;
            RedditExpansion result=new RedditExpansion(value,getter);
            result.value();found.add(result);
        }
        return found.size()==1?found.get(0):null;
    }
    public static RedditExpansion bind(Object screen) throws ReflectiveOperationException {
        List<RedditExpansion> matches=new ArrayList<>();
        for(Field field:screen.getClass().getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers()) || !field.getType().getName().startsWith("androidx.compose.animation.core."))continue;
            field.setAccessible(true);
            Object animator=field.get(screen);
            if(animator!=null) { RedditExpansion state=stateIn(animator);if(state!=null)matches.add(state); }
        }
        return matches.size()==1?matches.get(0):null;
    }
}
