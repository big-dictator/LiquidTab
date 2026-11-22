package io.github.offlineglass.hook.adapters.x;

/** Native scroll ownership and manual expansion, independent of Android objects/symbols. */
final class XChromeCollapsePolicy {
    private boolean expandedByTap;
    private boolean visible = true;
    private int selected = -1;

    boolean update(boolean enabled, boolean eligible, boolean nativeVisible, int tab) {
        if (!enabled || !eligible || nativeVisible != visible || tab != selected) expandedByTap = false;
        visible = nativeVisible;
        selected = tab;
        return enabled && eligible && !nativeVisible && !expandedByTap;
    }

    void expand() { expandedByTap = true; }

    static float mix(float start, float end, float progress) {
        return start + (end - start) * Math.max(0f, Math.min(1f, progress));
    }

    /** Underdamped step response, about 1.5% overshoot; settles exactly at the endpoint. */
    static float springFraction(float fraction) {
        if (fraction <= 0f) return 0f;
        if (fraction >= 1f) return 1f;
        double damping = .8, frequency = 12, damped = Math.sqrt(1-damping*damping);
        double phase = frequency*damped*fraction;
        return (float)(1-Math.exp(-damping*frequency*fraction)*
            (Math.cos(phase)+damping/damped*Math.sin(phase)));
    }

    /** Rebound is relative to the endpoint size, keeping a small button circular. */
    static float extent(float expanded, float collapsed, float progress) {
        if (progress < 0f) return expanded * (1 + Math.min(-progress, .025f));
        if (progress > 1f) return collapsed * (1 - Math.min((progress-1)*1.5f, .025f));
        return mix(expanded, collapsed, progress);
    }

    static float indicatorWidth(float expanded, float collapsed, float progress, boolean expanding) {
        return expanding ? expanded : collapsed;
    }

    /** Both endpoints are fixed in the left-anchored panel coordinate system. */
    static float navigationCenter(float expandedWidth, float collapsedSize, float padding,
                                  float visualPosition, int slots, float progress) {
        float slot = Math.max(1f, expandedWidth - 2*padding) / Math.max(1, slots);
        return mix(padding + (visualPosition + .5f)*slot, collapsedSize/2, progress);
    }

    /** Leading selection changes only size; its left edge stays at the original inset. */
    static float leadingWidth(float expanded, float collapsed, float progress) {
        return mix(expanded, collapsed, progress);
    }

    static float leadingCenter(float expandedWidth, float collapsedSize, float padding,
                               int slots, float progress) {
        float expandedSlot = Math.max(1f, expandedWidth - 2*padding) / Math.max(1, slots);
        return padding + leadingWidth(expandedSlot, collapsedSize - 2*padding, progress)/2;
    }
}
