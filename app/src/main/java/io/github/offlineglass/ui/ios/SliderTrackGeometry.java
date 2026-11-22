package io.github.offlineglass.ui.ios;

/** Geometry of the slider's fill; no animation, layout or optical policy lives here. */
public final class SliderTrackGeometry {
    private SliderTrackGeometry() {}

    public static int widthPx(int trackWidthPx, float progress) {
        // Same positive finite Float rounding as the former fastRoundToInt()
        // measurement. Do not quantize progress or change the animation itself.
        return Math.round(trackWidthPx * progress);
    }

    public static float leftPx(float trackWidthPx, int filledWidthPx, boolean rtl) {
        // Box's former TopStart placement anchored this child at the RTL end.
        return rtl ? trackWidthPx - filledWidthPx : 0f;
    }

    public static float radiusPx(int filledWidthPx, float trackHeightPx) {
        // RoundedCornerShape(50): percent of the smaller dimension, not a
        // fixed trackHeight / 2 (which changes the first few filled pixels).
        return Math.min(filledWidthPx, trackHeightPx) * 0.5f;
    }
}
