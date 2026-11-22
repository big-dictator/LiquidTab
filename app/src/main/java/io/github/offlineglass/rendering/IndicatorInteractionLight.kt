package io.github.offlineglass.rendering

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.hook.SquircleReference

/** Local interaction light only. No application identity or navigation state. */
internal class IndicatorInteractionLight(private val reuseOutline: Boolean = false) {
    companion object {
        fun verifyTranslation(): android.os.Bundle {
            fun render(outline: Path): IntArray {
                val bitmap = android.graphics.Bitmap.createBitmap(400, 180, android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.TRANSPARENT
                        setShadowLayer(16f, 0f, 0f, 0x20000000)
                    }
                    canvas.clipOutPath(outline)
                    canvas.drawPath(outline, paint)
                    return IntArray(400 * 180).also { bitmap.getPixels(it, 0, 400, 0, 0, 400, 180) }
                } finally { bitmap.recycle() }
            }
            var differentCases = 0
            var differentPixels = 0
            var cases = 0
            val reference = SquircleReference()
            var referenceDifferences = 0
            var referencePixelDifferences = 0
            val fixedPairing = linkedMapOf<String, Int>()
            for (width in floatArrayOf(144f, 199.25f, 212.5f)) {
                for (height in floatArrayOf(52f, 68f, 73.25f))
                for (dx in floatArrayOf(1f, 21.125f, 83.37f, 101.5f)) {
                    val original = RectF(24f, 44f, 24f + width, 44f + height)
                    val shifted = RectF(original).apply { offset(dx, 0f) }
                    val pairing = io.github.offlineglass.hook.checkFixedOutlinePairing(shifted, 34f)
                    fixedPairing[pairing] = (fixedPairing[pairing] ?: 0) + 1
                    val translated = Path().apply {
                        setBottomBarSquircle(original, 34f, BottomBarIndicatorStyle.CURVE)
                        offset(dx, 0f)
                    }
                    val rebuilt = Path().apply {
                        setBottomBarSquircle(shifted, 34f, BottomBarIndicatorStyle.CURVE, useSharedCache = false)
                    }
                    val optimized = Path().apply {
                        setBottomBarSquircle(shifted, 34f, BottomBarIndicatorStyle.CURVE, reference, false)
                    }
                    if (!optimized.approximate(0.01f).contentEquals(rebuilt.approximate(0.01f))) referenceDifferences++
                    val a = render(translated)
                    val b = render(rebuilt)
                    val c = render(optimized)
                    referencePixelDifferences += b.indices.count { b[it] != c[it] }
                    val differences = a.indices.count { a[it] != b[it] }
                    cases++
                    if (differences > 0) differentCases++
                    differentPixels += differences
                }
            }
            return android.os.Bundle().apply {
                putInt("cases", cases)
                putInt("differentCases", differentCases)
                putInt("differentPixels", differentPixels)
                putInt("referenceGeometryDifferences", referenceDifferences)
                putInt("referencePixelDifferences", referencePixelDifferences)
                putString("fixedPairing", fixedPairing.toString())
            }
        }
    }
    private inline fun <T> trace(name: String, action: () -> T): T {
        if (!io.github.offlineglass.BuildConfig.DEBUG || !android.os.Trace.isEnabled()) return action()
        android.os.Trace.beginSection(name)
        return try { action() } finally { android.os.Trace.endSection() }
    }
    private val path = Path()
    private val reference = if (reuseOutline) SquircleReference() else null
    private val lastBounds = RectF()
    private var lastRadius = Float.NaN
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun outer(canvas: Canvas, bounds: RectF, radius: Float, density: Float, dark: Boolean, progress: Float,
        outline: Path? = null) {
        if (progress <= 0.001f) return
        if (outline == null && (!reuseOutline || lastBounds != bounds || lastRadius != radius)) {
            trace("LiquidTab.OuterLight.Geometry") {
                path.setBottomBarSquircle(bounds, radius, BottomBarIndicatorStyle.CURVE, reference,
                    traceStages = reuseOutline)
            }
            if (reuseOutline) {
                lastBounds.set(bounds)
                lastRadius = radius
            }
        }
        trace("LiquidTab.OuterLight.Paint") {
        paint.shader = null
        paint.color = Color.TRANSPARENT
        paint.setShadowLayer(12f * density, 0f, 0f,
            Color.argb((255f * (if (dark) 0.18f else 0.12f) * progress.coerceIn(0f, 1f)).toInt(),
                if (dark) 255 else 0, if (dark) 255 else 0, if (dark) 255 else 0))
        }
        val save = canvas.save()
        // Never darken the interior in light mode, including the rounded edges.
        val contour = outline ?: path
        trace("LiquidTab.OuterLight.Clip") { canvas.clipOutPath(contour) }
        trace("LiquidTab.OuterLight.Draw") { canvas.drawPath(contour, paint) }
        canvas.restoreToCount(save)
        trace("LiquidTab.OuterLight.PaintReset") { paint.clearShadowLayer() }
    }
    fun inner(canvas: Canvas, bounds: RectF, progress: Float) {
        if (progress <= 0.001f) return
        paint.color = Color.WHITE
        paint.shader = RadialGradient(bounds.centerX(), bounds.centerY(),
            maxOf(bounds.width(), bounds.height()) * 0.65f,
            Color.argb((0.14f * 255f * progress.coerceIn(0f, 1f)).toInt(), 255, 255, 255),
            Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }
}
