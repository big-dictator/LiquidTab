package io.github.offlineglass.hook.adapters.douyin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Verified public home-tab semantics; implementation symbols never identify a capability. */
public final class DouyinTopBarContract {
    private DouyinTopBarContract() {}
    public record Ability(Method provider, Method current, Method position, Method count) {}
    public record Cell(Method id, Method content) {}
    public static Ability ability(Class<?> providerType, Class<?> viewType) {
        try {
            Method provider = providerType.getMethod("getTopTabAbility");
            if (!Modifier.isStatic(provider.getModifiers())) return null;
            Class<?> api = provider.getReturnType();
            if (!api.isInterface()) return null;
            Method current = api.getMethod("currentShowTab");
            Method position = api.getMethod("getTabPosition", String.class);
            Method count = api.getMethod("getTabCount");
            Method title = api.getMethod("getTitle", String.class);
            Method container = api.getMethod("getContainerView");
            if (current.getReturnType() != String.class || position.getReturnType() != int.class ||
                    count.getReturnType() != int.class || title.getReturnType() != String.class ||
                    !viewType.isAssignableFrom(container.getReturnType())) return null;
            for (Method method : new Method[]{current, position, count, title, container})
                if (Modifier.isStatic(method.getModifiers())) return null;
            return new Ability(provider, current, position, count);
        } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) { return null; }
    }
    public static Cell cell(Class<?> type, Class<?> groupType, Class<?> viewType) {
        if (!groupType.isAssignableFrom(type) || type == groupType) return null;
        try {
            Method id = type.getMethod("getCurrentTabId");
            Method content = type.getMethod("getContentView");
            Method delegate = type.getMethod("getTabViewDelegate");
            if (id.getReturnType() != String.class || !viewType.isAssignableFrom(content.getReturnType()) ||
                    !delegate.getReturnType().isInterface()) return null;
            for (Method method : new Method[]{id, content, delegate})
                if (Modifier.isStatic(method.getModifiers())) return null;
            return new Cell(id, content);
        } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) { return null; }
    }
}
