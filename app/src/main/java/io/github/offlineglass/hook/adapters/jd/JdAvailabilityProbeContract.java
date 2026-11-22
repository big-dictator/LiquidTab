package io.github.offlineglass.hook.adapters.jd;

import java.lang.reflect.*;
import java.util.*;

/** Resolve callback roles by their full native contract, not private member symbols. */
public final class JdAvailabilityProbeContract {
    public record Binding(Method probe, Field setting, Method reporter) {}
    private static boolean instance(Method m) { return !Modifier.isStatic(m.getModifiers()); }
    private static boolean signature(Method m, Class<?> result, Class<?>... args) {
        return instance(m) && m.getReturnType() == result && Arrays.equals(m.getParameterTypes(), args);
    }
    public static Binding resolve(Class<?> helper, Class<?> activity, Class<?> fragment) {
        var probes = Arrays.stream(helper.getDeclaredMethods()).filter(m ->
                m.getName().equals("isFragmentAvailable") && signature(m, boolean.class, String.class, String.class)).toList();
        var factories = Arrays.stream(helper.getDeclaredMethods()).filter(m ->
                m.getName().equals("newFragment") && signature(m, fragment, activity, String.class)).toList();
        if (probes.size() != 1 || factories.size() != 1)
            throw new IllegalStateException("availability/factory signature candidates=" + probes.size() + "/" + factories.size());
        List<Binding> matches = new ArrayList<>();
        for (var field : helper.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().isInterface()) continue;
            var methods = Arrays.asList(field.getType().getMethods());
            var reporters = methods.stream().filter(m -> signature(m, void.class,
                    String.class, String.class, String.class, Throwable.class)).toList();
            boolean gate = methods.stream().anyMatch(m -> m.getName().equals("isSwitchOpen") && signature(m, boolean.class, String.class));
            boolean pending = methods.stream().anyMatch(m -> signature(m, ArrayList.class, String.class));
            boolean create = methods.stream().anyMatch(m -> signature(m, fragment, ArrayList.class, String.class));
            boolean attached = methods.stream().anyMatch(m -> signature(m, void.class, activity));
            if (gate && pending && create && attached && reporters.size() == 1) {
                field.setAccessible(true);
                matches.add(new Binding(probes.getFirst(), field, reporters.getFirst()));
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("availability setting candidates=" + matches.size());
        return matches.getFirst();
    }
    public static Method reporterFor(Binding binding, Object setting) {
        if (setting == null || !binding.setting.getType().isInstance(setting))
            throw new IllegalStateException("availability setting instance mismatch");
        var contract = binding.reporter;
        var matches = Arrays.stream(setting.getClass().getMethods()).filter(m ->
                !Modifier.isAbstract(m.getModifiers()) && signature(m, void.class, contract.getParameterTypes())).toList();
        if (matches.size() != 1) throw new IllegalStateException("availability reporter candidates=" + matches.size());
        return matches.getFirst();
    }
}
