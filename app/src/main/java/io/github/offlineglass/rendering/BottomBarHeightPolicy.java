package io.github.offlineglass.rendering;

/** Pixel height shared by the manager and injected navigation bars, not top bars. */
public final class BottomBarHeightPolicy {
    private BottomBarHeightPolicy() {}

    public static int heightPx(float heightDp, float screenDensity) {
        // Preserve the manager's accepted five-tab reference and rounding.
        return Math.max(1, (int) Math.rint(heightDp * screenDensity * (497f / 520f)));
    }
}
