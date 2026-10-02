package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.ViewGroup

/** Meituan paints its tabs in custom c1 Views, including the text. */
internal class MeituanMainTabsRenderer {
    private data class Parts(val icon: Rect, val label: Rect?, val slotCenter: Float)
    private var bitmap: Bitmap? = null
    private var parts = emptyList<Parts?>()
    private var nextMeasure = 0L
    private var pixels = IntArray(0)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var preparedSource: ViewGroup? = null

    /** Do not fall back to the smaller generic projection while capture settles. */
    fun prepareFirstFrame(source: ViewGroup) {
        if (source.width <= 0 || source.height <= 0 || source.childCount != 5) return
        if (preparedSource === source && bitmap?.isRecycled == false &&
            bitmap?.width == source.width && bitmap?.height == source.height) return
        val initial = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val alpha = source.alpha
        try {
            if (alpha < .01f) source.alpha = 1f
            source.draw(Canvas(initial))
            nextMeasure = 0L
            accept(initial, source.childCount)
            preparedSource = source
        } finally {
            if (source.alpha != alpha) source.alpha = alpha
        }
        // The next shared capture replaces this bootstrap image. Let GC retire
        // it after Canvas/RenderThread users release it rather than recycling early.
    }

    fun accept(snapshot: Bitmap, count: Int) {
        val previous = bitmap
        bitmap = snapshot
        val now = SystemClock.uptimeMillis()
        if (previous?.width == snapshot.width && previous.height == snapshot.height &&
            parts.size == count && parts.all { it != null } && now < nextMeasure) return
        nextMeasure = now + 250L
        val width = snapshot.width
        val height = snapshot.height
        if (pixels.size != width * height) pixels = IntArray(width * height)
        snapshot.getPixels(pixels, 0, width, 0, 0, width, height)
        parts = (0 until count).map { index ->
            val left = index * width / count
            val right = (index + 1) * width / count
            val rows = (0 until height).filter { y ->
                var occupied = 0
                for (x in left until right) if ((pixels[y * width + x] ushr 24) > 32) occupied++
                occupied >= 2
            }
            if (rows.isEmpty()) return@map null
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
            val icon = Rect(left, rows.first(), right, iconEnd)
            val label = labelBand?.let { Rect(left, it.first, right, it.last + 1) }
            Parts(icon, if (index == 2) null else label, (left + right) / 2f)
        }
    }

    fun draw(canvas: Canvas, index: Int, centerX: Float, centerY: Float,
        projectedScale: Float): Boolean {
        val snapshot = bitmap?.takeUnless { it.isRecycled } ?: return false
        val tab = parts.getOrNull(index) ?: return false
        if (projectedScale <= 0f) return false
        val textScale = projectedScale * 1.2f
        val iconScale = textScale * if (index == 2) 1.12f else 1.15f
        // Keep the previous projected icon/text gap; enlarge only the artwork.
        val gap = tab.label?.let { (it.top - tab.icon.bottom).coerceAtLeast(0) * projectedScale } ?: 0f
        val total = tab.icon.height() * iconScale + (tab.label?.height() ?: 0) * textScale + gap
        val top = centerY - total / 2f
        fun drawPart(rect: Rect, y: Float, scale: Float) {
            destination.set(centerX + (rect.left - tab.slotCenter) * scale, y,
                centerX + (rect.right - tab.slotCenter) * scale, y + rect.height() * scale)
            canvas.drawBitmap(snapshot, rect, destination, paint)
        }
        drawPart(tab.icon, top, iconScale)
        tab.label?.let { drawPart(it, top + tab.icon.height() * iconScale + gap, textScale) }
        return true
    }

    fun clear() { bitmap = null; parts = emptyList(); pixels = IntArray(0); preparedSource = null }
}
