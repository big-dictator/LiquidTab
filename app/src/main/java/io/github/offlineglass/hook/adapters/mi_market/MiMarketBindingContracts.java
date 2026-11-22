package io.github.offlineglass.hook.adapters.mi_market;

import java.lang.reflect.Method;

/** Name-independent accessors and platform callback signatures; no APK symbol tables. */
public final class MiMarketBindingContracts {
    private MiMarketBindingContracts() {}
    public static Method[] uniqueAccessors(Class<?> owner, Class<?> value) {
        Method getter = null, setter = null;
        for (Method m : owner.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() == 0 && m.getReturnType() == value) {
                if (getter != null) return null;
                getter = m;
            }
            if (m.getReturnType() == void.class && m.getParameterCount() == 1 && m.getParameterTypes()[0] == value) {
                if (setter != null) return null;
                setter = m;
            }
        }
        return getter == null || setter == null ? null : new Method[]{getter, setter};
    }
    public static Method pageCallback(Class<?> client, Class<?> view, String platformName) {
        try {
            Method m = client.getMethod(platformName, view, String.class);
            return m.getReturnType() == void.class && !java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? m : null;
        } catch (ReflectiveOperationException e) { return null; }
    }
}
