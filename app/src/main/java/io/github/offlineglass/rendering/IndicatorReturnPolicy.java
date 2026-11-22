package io.github.offlineglass.rendering;

/** Release clock, not the press spring: the latter may still be rising after UP. */
public final class IndicatorReturnPolicy {
    private IndicatorReturnPolicy() {}
    public static float mix(float release, boolean touching, boolean returning, boolean ready) {
        if (touching || !returning || !ready) return 0f;
        return Math.max(0f, Math.min(1f, release));
    }
}
