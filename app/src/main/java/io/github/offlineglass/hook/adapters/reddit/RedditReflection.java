package io.github.offlineglass.hook.adapters.reddit;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

/** Name-independent, fail-closed reflection primitives, also exercised by JVM fixtures. */
public final class RedditReflection {
    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        protected Field[] computeValue(Class<?> type) { return type.getFields(); }
    };
    private static final ClassValue<Method[]> METHODS = new ClassValue<>() {
        protected Method[] computeValue(Class<?> type) { return type.getMethods(); }
    };
    public static Field field(Class<?> type, Predicate<Field> accept) {
        Field result = null;
        for (Field f : FIELDS.get(type)) if (!Modifier.isStatic(f.getModifiers()) && accept.test(f)) {
            if (result != null) return null;
            result = f;
        }
        if (result != null) result.setAccessible(true);
        return result;
    }
    public static Method method(Class<?> type, Predicate<Method> accept) {
        Method result = null;
        for (Method m : METHODS.get(type)) if (!Modifier.isStatic(m.getModifiers()) && accept.test(m)) {
            if (result != null) return null;
            result = m;
        }
        if (result != null) result.setAccessible(true);
        return result;
    }
    public static Field config(Class<?> type) {
        return field(type, f -> Iterable.class.isAssignableFrom(f.getType()));
    }
    public static Method children(Class<?> type) {
        return method(type, m -> List.class.isAssignableFrom(m.getReturnType()) &&
                Arrays.equals(m.getParameterTypes(), new Class<?>[]{boolean.class, boolean.class}));
    }
    public static Object callback(Object action) throws ReflectiveOperationException {
        Field match = field(action.getClass(), f -> {
            if (!f.getType().isInterface()) return false;
            try {
                Object value = f.get(action);
                return value != null && invoke(value.getClass()) != null;
            } catch (IllegalAccessException e) { return false; }
        });
        return match == null ? null : match.get(action);
    }
    public static Method invoke(Class<?> type) {
        return method(type, m -> m.getName().equals("invoke") && m.getParameterCount() == 0);
    }
    /** R8 can erase CanvasHolder's backing field to Object; validate its actual value. */
    public static List<Object> linkedInstances(Object owner, Class<?> role) throws ReflectiveOperationException {
        Set<Object> found=Collections.newSetFromMap(new IdentityHashMap<>());
        Field[] outer=owner.getClass().getDeclaredFields();
        if (outer.length>256) return List.of();
        for(Field f:outer) {
            if(Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive())continue;
            f.setAccessible(true);
            Object holder=f.get(owner);
            if(holder==null)continue;
            if(role.isInstance(holder)) { found.add(holder);continue; }
            Field[] inner=holder.getClass().getDeclaredFields();
            if(inner.length>128)continue;
            for(Field nested:inner) {
                if(Modifier.isStatic(nested.getModifiers()) || nested.getType().isPrimitive())continue;
                // Only read references; no recursive graph walk or calls into unknown objects.
                try {
                    nested.setAccessible(true);
                    Object value=nested.get(holder);
                    if(role.isInstance(value))found.add(value);
                } catch(RuntimeException | IllegalAccessException ignored) { }
            }
        }
        return new ArrayList<>(found);
    }
    private RedditReflection() {}
}
