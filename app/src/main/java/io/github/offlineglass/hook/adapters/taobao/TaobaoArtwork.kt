package io.github.offlineglass.hook.adapters.taobao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

/** Taobao's packaged tab artwork and its asynchronous native-row validation. */
internal class TaobaoArtwork(private val context: Context) {
    private val fixed = arrayOfNulls<Bitmap>(5)
    private val attempted = BooleanArray(5)
    private val accentFilter=BlendModeColorFilter(0xFFFF5000.toInt(),BlendMode.SRC_IN)
    private val darkFilter=BlendModeColorFilter(0xFFE5E5E5.toInt(),BlendMode.SRC_IN)
    private val lightFilter=BlendModeColorFilter(0xFF171717.toInt(),BlendMode.SRC_IN)
    private data class Bands(val icon: Rect, val label: Rect?)
    private val bands = arrayOfNulls<Bands>(5)
    private val destination = RectF()
    private val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // UI validation reads one native slot at a time; published bitmaps remain
    // unchanged. Avoid thousands of Java/native getPixel crossings per capture.
    private var slotPixels=IntArray(0)
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
                    // Native NavigationTabIndicatorView.loadLocalImage/loadImage
                    // installs StateListDrawable via setBackgroundDrawable on
                    // mIconView; ImageView.drawable may legitimately stay null.
                    if (view is ImageView && (view.drawable != null || view.background != null)) hasArtwork = true
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
            val width=right-left
            val required=width*candidate.height
            if(slotPixels.size<required)slotPixels=IntArray(required)
            candidate.getPixels(slotPixels,0,width,left,0,width,candidate.height)
            val pixels=TaobaoArtworkPixels.inspect(slotPixels,width,candidate.height,selectedHome)
            if(!pixels.visible())return false
            if(selectedHome&&pixels.centerY().isFinite())selectedHomeContentCenterY=pixels.centerY()
        }
        return true
    }

    @Synchronized fun replaceVideoSlot(candidate: Bitmap) {
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

    @Synchronized fun drawFixedSlot(canvas: Canvas, index: Int, selectedIndex: Int, dark: Boolean,
                      centerX: Float, centerY: Float, boxSize: Float, iconOnly: Boolean = false): Boolean {
        val artwork = load(index) ?: return false
        if (artwork.width <= 0 || artwork.height <= 0 || boxSize <= 0f) return false
        val scale = boxSize / artwork.width
        val split = bands[index] ?: findBands(artwork).also { bands[index] = it }
        val iconHeight = split.icon.height() * scale * 1.2f
        val caption = split.label.takeUnless { iconOnly }
        val labelHeight = (caption?.height() ?: 0) * scale
        val gap = caption?.let { (it.top - split.icon.bottom).coerceAtLeast(0) * scale } ?: 0f
        val top = centerY - (iconHeight + gap + labelHeight) / 2f
        copyPaint.alpha = 255
        copyPaint.colorFilter = if(selectedIndex==index) accentFilter else if(dark) darkFilter else lightFilter
        val iconWidth = split.icon.width() * scale * 1.2f
        destination.set(centerX - iconWidth / 2f, top, centerX + iconWidth / 2f, top + iconHeight)
        canvas.drawBitmap(artwork, split.icon, destination, copyPaint)
        caption?.let { label ->
            val labelWidth = label.width() * scale
            destination.set(centerX - labelWidth / 2f, top + iconHeight + gap,
                centerX + labelWidth / 2f, top + iconHeight + gap + labelHeight)
            canvas.drawBitmap(artwork, label, destination, copyPaint)
        }
        copyPaint.colorFilter = null
        return true
    }

    /** Split native icon/label pixels once, preserving the actual label size and gap. */
    private fun findBands(bitmap: Bitmap): Bands {
        val pixels=IntArray(bitmap.width*bitmap.height)
        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        val rows = BooleanArray(bitmap.height)
        for (y in rows.indices) for (x in 0 until bitmap.width) {
            if (Color.alpha(pixels[y*bitmap.width+x]) > 24) { rows[y] = true; break }
        }
        val first = rows.indexOfFirst { it }.coerceAtLeast(0)
        val last = rows.indexOfLast { it }.coerceAtLeast(first)
        var gapStart = -1; var gapEnd = -1; var run = -1
        for (y in first..last) {
            if (!rows[y] && run < 0) run = y
            if (rows[y] && run >= 0) {
                if (y - run > gapEnd - gapStart) { gapStart = run; gapEnd = y }
                run = -1
            }
        }
        fun bounds(top: Int, bottom: Int): Rect {
            var left = bitmap.width; var right = 0
            for (y in top until bottom) for (x in 0 until bitmap.width) {
                if (Color.alpha(pixels[y*bitmap.width+x]) > 24) {
                    left = minOf(left, x); right = maxOf(right, x + 1)
                }
            }
            return Rect(if (left < right) left else 0, top,
                if (left < right) right else bitmap.width, bottom)
        }
        return if (gapStart > first && gapEnd <= last && gapEnd - gapStart >= 2)
            Bands(bounds(first, gapStart), bounds(gapEnd, last + 1))
        else Bands(bounds(first, last + 1), null)
    }

    @Synchronized private fun load(index: Int): Bitmap? {
        if (index !in fixed.indices) return null
        fixed[index]?.takeUnless(Bitmap::isRecycled)?.let { return it }
        if(attempted[index])return null
        attempted[index]=true
        val id = context.resources.getIdentifier(resourceNames[index], "drawable", "com.taobao.taobao")
        if (id == 0) return null
        return BitmapFactory.decodeResource(context.resources, id)?.also { fixed[index] = it }
    }

    @Synchronized fun dispose() {
        slotPixels=IntArray(0)
        fixed.indices.forEach { index ->
            // Submitted hardware display lists can outlive this host.
            fixed[index] = null
            attempted[index]=false
            bands[index] = null
        }
    }
}
