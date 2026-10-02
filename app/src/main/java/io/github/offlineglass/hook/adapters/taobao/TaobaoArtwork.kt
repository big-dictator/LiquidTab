package io.github.offlineglass.hook.adapters.taobao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

/** Taobao's packaged tab artwork and its asynchronous native-row validation. */
internal class TaobaoArtwork(private val context: Context) {
    private val fixed = arrayOfNulls<Bitmap>(5)
    private val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val resourceNames = arrayOf(
        "uik_nav_home_normal", "uik_nav_video_normal", "uik_nav_message_normal",
        "uik_nav_cart_normal", "uik_nav_my_normal",
    )
    var selectedHomeContentCenterY = Float.NaN
        private set

    fun acceptCandidate(source: ViewGroup?, candidate: Bitmap, selectedIndex: Int): Boolean {
        source ?: return false
        if (source.childCount < 5 || candidate.width < 5 || candidate.height <= 0) return false
        for (slot in 0 until 5) {
            if (slot == 1 && load(1) != null) continue
            val root = source.getChildAt(slot) ?: return false
            var hasLabel = false
            var hasArtwork = false
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += root to 0
            while (stack.isNotEmpty()) {
                val (view, depth) = stack.removeLast()
                if (view.visibility == View.VISIBLE && view.alpha > 0.05f && view.width > 0 && view.height > 0) {
                    if (view is TextView && !view.text.isNullOrBlank()) hasLabel = true
                    if (view is ImageView && view.drawable != null) hasArtwork = true
                    if (view !is ViewGroup && view !is TextView && view !is ImageView) hasArtwork = true
                }
                if (view is ViewGroup && depth < 8) {
                    for (i in 0 until view.childCount) stack += view.getChildAt(i) to depth + 1
                }
            }
            val selectedHome = slot == 0 && selectedIndex == 0
            if ((!hasLabel && !selectedHome) || !hasArtwork) return false
            val left = slot * candidate.width / 5
            val right = (slot + 1) * candidate.width / 5
            var visibleSamples = 0
            val stepX = ((right - left) / 24).coerceAtLeast(1)
            val stepY = (candidate.height / 20).coerceAtLeast(1)
            var y = 0
            while (y < candidate.height) {
                var x = left
                while (x < right) {
                    if (Color.alpha(candidate.getPixel(x, y)) > 24) visibleSamples++
                    x += stepX
                }
                y += stepY
            }
            if (visibleSamples < 4) return false
            if (selectedHome) {
                var minY = candidate.height
                var maxY = -1
                var pixelY = 0
                while (pixelY < candidate.height) {
                    var pixelX = left
                    while (pixelX < right) {
                        if (Color.alpha(candidate.getPixel(pixelX, pixelY)) > 24) {
                            minY = min(minY, pixelY)
                            maxY = maxOf(maxY, pixelY)
                        }
                        pixelX += 2
                    }
                    pixelY += 2
                }
                if (maxY > minY) selectedHomeContentCenterY = (minY + maxY) / 2f
            }
        }
        return true
    }

    fun replaceVideoSlot(candidate: Bitmap) {
        val artwork = load(1) ?: return
        if (candidate.width < 5 || candidate.height <= 0) return
        val slotWidth = candidate.width / 5
        val left = slotWidth
        val canvas = Canvas(candidate)
        canvas.save()
        canvas.clipRect(left, 0, left + slotWidth, candidate.height)
        canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        val scale = min(slotWidth.toFloat() / artwork.width.coerceAtLeast(1),
            candidate.height.toFloat() / artwork.height.coerceAtLeast(1))
        val drawWidth = artwork.width * scale
        val drawHeight = artwork.height * scale
        val destination = RectF(left + (slotWidth - drawWidth) / 2f,
            (candidate.height - drawHeight) / 2f,
            left + (slotWidth + drawWidth) / 2f,
            (candidate.height + drawHeight) / 2f)
        canvas.drawBitmap(artwork, null, destination, copyPaint)
        canvas.restore()
    }

    fun drawFixedSlot(canvas: Canvas, index: Int, selectedIndex: Int, dark: Boolean,
                      centerX: Float, centerY: Float, boxSize: Float): Boolean {
        val artwork = load(index) ?: return false
        if (artwork.width <= 0 || artwork.height <= 0 || boxSize <= 0f) return false
        val color = if (selectedIndex == index) 0xFFFF5000.toInt()
            else if (dark) 0xFFE5E5E5.toInt() else 0xFF171717.toInt()
        val half = boxSize / 2f
        copyPaint.alpha = 255
        copyPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
        canvas.drawBitmap(artwork, null,
            RectF(centerX - half, centerY - half, centerX + half, centerY + half), copyPaint)
        copyPaint.colorFilter = null
        return true
    }

    private fun load(index: Int): Bitmap? {
        if (index !in fixed.indices) return null
        fixed[index]?.takeUnless(Bitmap::isRecycled)?.let { return it }
        val id = context.resources.getIdentifier(resourceNames[index], "drawable", "com.taobao.taobao")
        if (id == 0) return null
        return BitmapFactory.decodeResource(context.resources, id)?.also { fixed[index] = it }
    }

    fun dispose() {
        fixed.forEachIndexed { index, bitmap ->
            bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            fixed[index] = null
        }
    }
}
