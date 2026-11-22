package io.github.offlineglass.hook.adapters.bilibili_ambient;

import java.util.List;

/** Only the verified native video-render role qualifies, never a largest-view heuristic. */
public final class BilibiliAmbientSources {
    private BilibiliAmbientSources() {}
    public static <T> T unique(List<T> surfaces, Class<?> nativeRole) {
        T result = null;
        for (T surface : surfaces) {
            if (!nativeRole.isInstance(surface)) continue;
            if (result != null && result != surface) return null;
            result = surface;
        }
        return result;
    }
}
