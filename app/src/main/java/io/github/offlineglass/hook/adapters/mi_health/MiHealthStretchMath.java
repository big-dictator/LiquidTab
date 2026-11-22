package io.github.offlineglass.hook.adapters.mi_health;
/** Exact outside-viewport branch of AOSP HWUI StretchEffect's inverse sample mapping. */
public final class MiHealthStretchMath {
    private MiHealthStretchMath() {}
    public static float bottomTranslation(float height, float nativeVector) {
        if (!(height > 0) || !Float.isFinite(nativeVector) || nativeVector >= 0) return 0;
        return height / (1f + Math.abs(nativeVector)) - height;
    }
}
