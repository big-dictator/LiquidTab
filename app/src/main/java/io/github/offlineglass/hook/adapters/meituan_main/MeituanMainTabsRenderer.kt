package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.XIAOTUAN

/** Native semantic glyph snapshots; independent of obfuscated implementation names. */
internal class MeituanMainTabsRenderer {
    private data class Parts(val icon: Rect, val label: Rect?, val iconCenter: Float, val labelCenter: Float?)
    private var bitmap: Bitmap? = null
    private var parts = emptyMap<Int, Parts?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    fun accept(snapshot: Bitmap, bounds: Map<Int, Rect>): Boolean {
        bitmap = snapshot
        val width = snapshot.width
        val height = snapshot.height
        val pixels = IntArray(width * height)
        snapshot.getPixels(pixels, 0, width, 0, 0, width, height)
        parts = bounds.mapValues { (index, slot) ->
            val left = slot.left
            val right = slot.right
            val rows = (0 until height).filter { y ->
                var occupied = 0
                for (x in left until right) if ((pixels[y * width + x] ushr 24) > 32) occupied++
                occupied >= 2
            }
            if (rows.isEmpty()) return@mapValues null
            val bands = mutableListOf<IntRange>()
            var start = rows.first()
            var end = start
            for (y in rows.drop(1)) {
                if (y - end > 3) { bands += start..end; start = y }
                end = y
            }
            bands += start..end
            // The last native band is the label; all upper bands include the
            // complete artwork and notification badges. Never crop the centre icon.
            val labelBand = bands.last().takeIf { bands.size >= 2 && it.first > height * .5f }
            val iconEnd = if (labelBand != null) bands[bands.lastIndex - 1].last + 1 else rows.last() + 1
            fun visibleBounds(top: Int, bottom: Int): Rect? {
                var minX = right
                var maxX = left - 1
                var minY = bottom
                var maxY = top - 1
                for (y in top until bottom) for (x in left until right) {
                    if ((pixels[y * width + x] ushr 24) > 32) {
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                }
                return if (maxX >= minX) Rect(minX, minY, maxX + 1, maxY + 1) else null
            }
            val icon = visibleBounds(rows.first(), iconEnd) ?: return@mapValues null
            val label = labelBand?.let { visibleBounds(it.first, it.last + 1) }
            // Preserve the native slot axis. Asymmetric badges must not drag
            // the icon sideways, independently in selected/unselected images.
            val axis = (left + right) / 2f
            Parts(icon, if (index == XIAOTUAN) null else label,
                if (index == XIAOTUAN) icon.exactCenterX() else axis, label?.let { axis })
        }
        return parts.values.all { it != null }
    }

    fun draw(canvas: Canvas, index: Int, centerX: Float, centerY: Float,
        projectedScale: Float, maxIconSize: Float = Float.POSITIVE_INFINITY, iconOnly: Boolean = false): Boolean {
        val snapshot = bitmap?.takeUnless { it.isRecycled } ?: return false
        val tab = parts[index] ?: return false
        if (projectedScale <= 0f) return false
        val textScale = projectedScale * 1.2f
        val iconScale = minOf(textScale * if (index == XIAOTUAN) 1.12f else 1.15f,
            maxIconSize / tab.icon.width(), maxIconSize / tab.icon.height())
        // Keep the previous projected icon/text gap; enlarge only the artwork.
        val caption = tab.label.takeUnless { iconOnly }
        val gap = caption?.let { (it.top - tab.icon.bottom).coerceAtLeast(0) * projectedScale } ?: 0f
        val total = tab.icon.height() * iconScale + (caption?.height() ?: 0) * textScale + gap
        val top = centerY - total / 2f
        fun drawPart(rect: Rect, sourceCenter: Float, y: Float, scale: Float) {
            destination.set(centerX + (rect.left - sourceCenter) * scale, y,
                centerX + (rect.right - sourceCenter) * scale, y + rect.height() * scale)
            canvas.drawBitmap(snapshot, rect, destination, paint)
        }
        drawPart(tab.icon, tab.iconCenter, top, iconScale)
        caption?.let { drawPart(it, tab.labelCenter ?: tab.iconCenter,
            top + tab.icon.height() * iconScale + gap, textScale) }
        return true
    }

}
