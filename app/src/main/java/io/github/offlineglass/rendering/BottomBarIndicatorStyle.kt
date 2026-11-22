package io.github.offlineglass.rendering
import io.github.offlineglass.hook.setBottomBarSquircle
import kotlin.math.roundToInt

/** Shared, user-accepted bottom-tab lens style. No app identity or page logic. */
internal object BottomBarIndicatorStyle {
    /** Shared resting thumb fill; active refraction remains untinted. */
    fun restingFill(dark: Boolean, selectedAccent: Boolean, accent: Int, alpha: Float): Int {
        val color = if (selectedAccent) accent else if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        return android.graphics.Color.argb((alpha * 255f).toInt(),
            android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
    }
    fun idleLiftAlpha(dark: Boolean) = if (dark) .012f else .008f
    fun idleShadeAlpha(dark: Boolean) = if (dark) .13f else .04f
    fun idleBlueAlpha(dark: Boolean) = if (dark) .025f else .018f
    fun idlePinkAlpha(dark: Boolean) = if (dark) .020f else .016f
    const val PRESS_SCALE = 1.48f
    const val PRESS_GROWTH_GAIN = 1.08f
    // Reference five-tab bar: 497dpi app geometry on a 520dpi display.
    // Screen-relative conversion prevents per-app density overrides changing height.
    fun compactBarHeightPx(heightDp: Float, fallbackDensity: Float): Int {
        val screenDensity = android.content.res.Resources.getSystem().configuration.densityDpi
            .takeIf { it > 0 }?.div(160f) ?: fallbackDensity
        return BottomBarHeightPolicy.heightPx(heightDp, screenDensity)
    }
    const val COMPACT_TAB_PRESS_SCALE_X = 1.32f
    /** Wider slots need less horizontal growth; vertical optics stay unchanged. */
    fun pressedScaleX(tabCount: Int, fullScale: Float = PRESS_SCALE): Float =
        if (tabCount in 1..4) 1f + (fullScale - 1f) *
            ((COMPACT_TAB_PRESS_SCALE_X - 1f) / (PRESS_SCALE - 1f)) else fullScale
    const val CURVE = -1.20f
    fun indicatorCurve(tabCount: Int, pressProgress: Float, activeCurve: Float = CURVE): Float =
        if (tabCount in 1..4) -1.10f + (activeCurve + 1.10f) * pressProgress.coerceIn(0f, 1f)
        else activeCurve
    const val SPECULAR_WIDTH_DP = 1f
    const val SPECULAR_MIN_ALPHA = 0.8f
    const val HIDDEN_SPECULAR_GAIN = 0.25f
    const val RESTING_TINT_RETURN_MS = 240L
    // The moving outline is un-clipped and already in final draw coordinates.
    // Match the visible width of the shared, inside-clipped hairline in light mode.
    fun expandedRimStrokeDp(dark: Boolean): Float =
        if (dark) .45f else GlassEdgeHighlight.BLACK_HAIRLINE_STROKE_DP / 2f
    fun expandedRimAlpha(dark: Boolean, progress: Float): Int =
        ((if (dark) 135 else GlassEdgeHighlight.LIGHT_BLACK_HAIRLINE_ALPHA) *
            progress.coerceIn(0f, 1f)).roundToInt()
    private val contours = android.util.LruCache<List<Float>, FloatArray>(16)
    fun contour(w: Float, h: Float, radius: Float, curve: Float = CURVE): FloatArray {
        val key = listOf(w, h, radius, curve)
        contours.get(key)?.let { return it }
        val path = android.graphics.Path().apply {
            setBottomBarSquircle(android.graphics.RectF(0f, 0f, w, h), radius, curve)
        }
        val measure = android.graphics.PathMeasure(path, true)
        val result = FloatArray(128)
        val point = FloatArray(2)
        for (i in 0 until 64) {
            measure.getPosTan(measure.length * i / 64f, point, null)
            result[i * 2] = point[0]; result[i * 2 + 1] = point[1]
        }
        contours.put(key, result)
        return result
    }
}
