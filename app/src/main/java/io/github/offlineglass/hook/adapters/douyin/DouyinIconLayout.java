package io.github.offlineglass.hook.adapters.douyin;

/** Density-independent navigation geometry, shared by artwork and fallback ink. */
public final class DouyinIconLayout {
    private DouyinIconLayout() {}
    public static float center(int nativeIndex, int[] visible, float inset, float slotWidth) {
        int found = -1;
        for (int i = 0; i < visible.length; i++) if (visible[i] == nativeIndex) {
            if (found >= 0) return Float.NaN;
            found = i;
        }
        return found < 0 ? Float.NaN : inset + (found + .5f) * slotWidth;
    }
    public static float groupOffset(float height, float iconHeight, float labelBottom, boolean iconOnly) {
        if (iconOnly) return height * .5f - height * .30f;
        float top = height * .30f - iconHeight * .5f;
        float bottom = height * .78f + labelBottom;
        return height * .5f - (top + bottom) * .5f;
    }
}
