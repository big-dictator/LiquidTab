package io.github.offlineglass.hook.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection helpers owned by LiquidTab; the modern framework provides no legacy helpers. */
public final class XposedHelpers {
    private XposedHelpers() {}
    private record Key(Class<?> type, String name, java.util.List<Class<?>> arguments) {}
    private record Resolution(Method method) {}
    // A missing method is immutable for a loaded Class, unlike a failed invocation.
    // Bound retained class/signature keys; reads stay lock-free on the render path.
    private static final int MAX_RESOLUTIONS = 512;
    private static final Resolution ABSENT = new Resolution(null);
    private static final ConcurrentHashMap<Key, Resolution> cache = new ConcurrentHashMap<>();
    private static final java.util.ArrayDeque<Key> insertionOrder = new java.util.ArrayDeque<>();
    private static final class NullArgument {}
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException error) { throw new IllegalArgumentException(name, error); }
    }
    public static Object callMethod(Object object, String name, Object... args) {
        Method method = resolution(object.getClass(), name, args).method;
        if (method == null) throw new IllegalArgumentException("No matching method: " + object.getClass().getName() + "." + name);
        try { return method.invoke(object, args); }
        catch (InvocationTargetException error) { return sneakyThrow(error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    /** Same overload/accessibility contract as callMethod, without invoking application code.
     * Only a deterministic no-compatible-method result returns false. Access failures escape.
     */
    public static boolean hasMethod(Object object, String name, Object... args) {
        return resolution(object.getClass(), name, args).method != null;
    }
    /** Capability probe before allocating a platform view or other target instance. */
    public static boolean hasMethod(Class<?> type, String name, Object... args) {
        return resolution(type, name, args).method != null;
    }
    private static Resolution resolution(Class<?> type, String name, Object[] args) {
        var types = new java.util.ArrayList<Class<?>>();
        for (Object arg : args) types.add(arg == null ? NullArgument.class : arg.getClass());
        Key key = new Key(type, name, types);
        Resolution cached = cache.get(key);
        if (cached != null) return cached;
        synchronized (cache) {
            cached = cache.get(key);
            if (cached != null) return cached;
            // Accessibility/reflection errors are not cached. A later call must
            // be allowed to retry if the platform or caller access changes.
            Method method = resolve(type, name, args);
            Resolution result = method == null ? ABSENT : new Resolution(method);
            if (cache.size() >= MAX_RESOLUTIONS) cache.remove(insertionOrder.removeFirst());
            cache.put(key, result);
            insertionOrder.addLast(key);
            return result;
        }
    }
    private static Method resolve(Class<?> type, String name, Object[] args) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        java.util.LinkedHashSet<Method> candidates = new java.util.LinkedHashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            candidates.addAll(java.util.Arrays.asList(current.getDeclaredMethods()));
        }
        // Include inherited interface default methods as well as class declarations.
        candidates.addAll(java.util.Arrays.asList(type.getMethods()));
            for (Method method : candidates) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                int score = 0;
                Class<?>[] parameters = method.getParameterTypes();
                for (int i = 0; i < args.length; i++) {
                    if (args[i] == null) { if (parameters[i].isPrimitive()) { score = Integer.MAX_VALUE; break; } score += 10; }
                    else if (boxed(parameters[i]) == args[i].getClass()) { /* exact */ }
                    else if (parameters[i].isPrimitive() && widening(args[i].getClass(), parameters[i]) > 0)
                        score += widening(args[i].getClass(), parameters[i]);
                    else if (boxed(parameters[i]).isInstance(args[i])) score += distance(args[i].getClass(), parameters[i]);
                    else { score = Integer.MAX_VALUE; break; }
                }
                if (score < bestScore) { best = method; bestScore = score; }
            }
        if (best == null) return null;
        best.setAccessible(true);
        return best;
    }
    private static int distance(Class<?> source, Class<?> target) {
        var queue = new java.util.ArrayDeque<Class<?>>();
        var distances = new java.util.HashMap<Class<?>, Integer>();
        queue.add(source); distances.put(source, 0);
        while (!queue.isEmpty()) {
            Class<?> next = queue.remove();
            int cost = distances.get(next);
            if (next == target) return cost;
            var parents = new java.util.ArrayList<Class<?>>(java.util.Arrays.asList(next.getInterfaces()));
            if (next.getSuperclass() != null) parents.add(next.getSuperclass());
            for (Class<?> parent : parents) if (distances.putIfAbsent(parent, cost + 1) == null) queue.add(parent);
        }
        return 100;
    }
    private static int widening(Class<?> source, Class<?> target) {
        String allowed = source == Byte.class ? "short,int,long,float,double" :
                source == Short.class || source == Character.class ? "int,long,float,double" :
                source == Integer.class ? "long,float,double" : source == Long.class ? "float,double" :
                source == Float.class ? "double" : "";
        var names = allowed.split(",");
        for (int i = 0; i < names.length; i++) if (target.getName().equals(names[i])) return i + 2;
        return -1;
    }
    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return type;
    }
    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable error) throws E { throw (E) error; }
}
