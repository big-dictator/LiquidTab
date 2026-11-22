package io.github.offlineglass.hook.adapters.jd;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stable, inherited native navigation methods and the role order expected by the host. */
public final class JdNavigationContract {
    private JdNavigationContract() {}

    public record Api(Class<?> group, Class<?> button, Method selection, Method id,
                      Method touch, Method icon, Method label, Method badge, Method title) {}

    public static Api resolve(Class<?> group, Class<?> button, Class<?> viewGroup, Class<?> view)
            throws NoSuchMethodException {
        if (!viewGroup.isAssignableFrom(group) || !viewGroup.isAssignableFrom(button)) {
            throw new IllegalArgumentException("Navigation API must belong to view groups");
        }
        return new Api(group, button, getter(group, "getCheckId", int.class),
                getter(button, "getNavigationId", int.class), getter(button, "getTouchView", view),
                getter(button, "getNaviIconView", view), getter(button, "getTabText", view),
                getter(button, "getRedPointView", view), getter(button, "getLabel", String.class));
    }

    private static Method getter(Class<?> owner, String name, Class<?> type) throws NoSuchMethodException {
        Method method = owner.getMethod(name);
        if (Modifier.isStatic(method.getModifiers()) || !type.isAssignableFrom(method.getReturnType())) {
            throw new IllegalArgumentException("Invalid native navigation getter: " + name);
        }
        return method;
    }

    public static boolean rolesMatch(List<Integer> ids, List<Set<Integer>> roles) {
        if (ids.size() != roles.size() || new HashSet<>(ids).size() != ids.size()) return false;
        for (int index = 0; index < ids.size(); index++) {
            if (!roles.get(index).contains(ids.get(index))) return false;
        }
        return true;
    }
}
