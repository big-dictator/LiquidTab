package io.github.offlineglass.hook.adapters.douyin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Native pinch/feed chrome semantics, independent of implementation names. */
public final class DouyinFeedChromeContract {
    private DouyinFeedChromeContract() {}
    public static Method resolve(Class<?> type, Class<?> frameBase, Class<?> viewBase, Class<?> model) {
        if (!frameBase.isAssignableFrom(type) || type == frameBase) return null;
        try {
            Method mode = type.getMethod("getMode");
            Method bottom = type.getMethod("getBottomLayout");
            Method guide = type.getMethod("getShowingGuideType");
            Method pinch = type.getMethod("setCanEnterPinch", boolean.class);
            if (mode.getReturnType() != String.class || !viewBase.isAssignableFrom(bottom.getReturnType()) ||
                    guide.getReturnType() != int.class || pinch.getReturnType() != void.class) return null;
            for (Method method : new Method[]{mode, bottom, guide, pinch}) {
                if (Modifier.isStatic(method.getModifiers())) return null;
            }
            boolean ownsVideo = false;
            for (Class<?> owner = type; owner != null && owner != frameBase; owner = owner.getSuperclass()) {
                for (Field field : owner.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) && model.isAssignableFrom(field.getType())) ownsVideo = true;
                }
            }
            return ownsVideo ? bottom : null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException unavailable) {
            return null;
        }
    }
}
