package io.github.offlineglass.hook.adapters.xianyu;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Verified public splash protocol; implementation identity comes from the native module registry. */
public final class XianyuSplashContract {
    private XianyuSplashContract() {}

    public record Binding(Method cold, Method hot, Method share, Method prefetch,
                          Method setShowing, Method releaseActions) {}

    public static Binding resolve(Class<?> implementation, Class<?> protocol, Class<?> activity)
            throws ReflectiveOperationException {
        if (!protocol.isInterface() || !protocol.isAssignableFrom(implementation)
                || implementation.isInterface() || Modifier.isAbstract(implementation.getModifiers())) {
            throw new IllegalArgumentException("Splash protocol owner mismatch");
        }
        exact(protocol, "isSplashAdShowing", boolean.class);
        exact(protocol, "addActionAfterSplashAd", void.class, Runnable.class);
        return new Binding(
                exact(implementation, "loadSplashAd", void.class, activity),
                exact(implementation, "loadSplashAdHotStart", void.class),
                exact(implementation, "loadToCSplashAd", void.class),
                exact(implementation, "requestAsyncSplashAd", void.class, boolean.class),
                exact(implementation, "setSplashAdShowing", void.class, boolean.class),
                exact(implementation, "runActionsAfterSplashAd", void.class));
    }

    private static Method exact(Class<?> owner, String semanticName, Class<?> result, Class<?>... args)
            throws NoSuchMethodException {
        Method[] matches = Arrays.stream(owner.getMethods()).filter(m ->
                m.getName().equals(semanticName) && m.getReturnType() == result
                        && Arrays.equals(m.getParameterTypes(), args)
                        && !Modifier.isStatic(m.getModifiers()) && !m.isBridge() && !m.isSynthetic())
                .toArray(Method[]::new);
        if (matches.length != 1) throw new NoSuchMethodException("Missing or ambiguous splash role: " + semanticName);
        return matches[0];
    }
}
