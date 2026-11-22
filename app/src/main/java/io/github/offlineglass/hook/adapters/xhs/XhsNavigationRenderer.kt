package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.localizeNavigationLabel

/** Artwork state belongs to the optical pass, never to the current page index. */
internal class XhsNavigationRenderer {
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame): Unit = with(frame) {
        if (slotCount != 5 || width <= 0 || height <= 0) return
        val normal = if (darkGlass) Color.rgb(232, 230, 235) else Color.rgb(40, 40, 40)
        labelPaint.textSize = 15f * 1.2f * scaledDensity
        val metrics = labelPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        barLayout?.let { layout ->
            // The shared host already draws this action's own glass and shadow.
            // Match the glass radius so the untinted face does not form a ring.
            if (!hideSelectedForOptics) {
                val cx = layout.actionLeft + layout.actionSize / 2f
                actionPaint.style = Paint.Style.FILL
                // White backdrops wash a low-alpha red into pastel pink. Keep
                // the same brand hue but increase coverage in light mode;
                // the remaining transparency still exposes the live optics.
                val tintAlpha = if (darkGlass) 70 else 190
                actionPaint.color = (BRAND_RED and 0x00FFFFFF) or (tintAlpha shl 24)
                canvas.drawCircle(cx, height / 2f, layout.actionSize / 2f, actionPaint)
                drawPlus(canvas, cx, height / 2f, 9f * density, density)
            }
        }
        for ((visualIndex, index) in enabledIndices.withIndex()) {
            val cx = 4f * density + (visualIndex + .5f) * slotWidth
            val saved = canvas.save()
            canvas.scale(extraScale, extraScale, cx, height / 2f)
            if (index == 2) {
                val half = 20f * density
                rect.set(cx - half, height / 2f - half, cx + half, height / 2f + half)
                actionPaint.style = Paint.Style.FILL
                actionPaint.color = BRAND_RED
                canvas.drawRoundRect(rect, 12f * density, 12f * density, actionPaint)
                drawPlus(canvas, cx, height / 2f, 7f * density, density)
            } else {
                labelPaint.color = if (hideSelectedForOptics) accentColor else normal
                canvas.drawText(localizeNavigationLabel(LABELS[index]), cx, baseline, labelPaint)
            }
            canvas.restoreToCount(saved)
        }
    }
    private fun drawPlus(canvas: Canvas, x: Float, y: Float, half: Float, density: Float) {
        actionPaint.style = Paint.Style.STROKE
        actionPaint.strokeWidth = 3.2f * density
        actionPaint.color = Color.WHITE
        canvas.drawLine(x - half, y, x + half, y, actionPaint)
        canvas.drawLine(x, y - half, x, y + half, actionPaint)
        actionPaint.style = Paint.Style.FILL
    }
    companion object {
        const val BRAND_RED = 0xFFFF2442.toInt()
        private val LABELS = arrayOf("首页", "市集", "", "消息", "我")
    }
}
