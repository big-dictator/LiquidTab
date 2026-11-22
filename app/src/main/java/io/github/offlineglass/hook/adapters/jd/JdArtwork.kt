package io.github.offlineglass.hook.adapters.jd

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.NavigationLabelLocalization
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Native icon pixels, semantic captions and native badges have separate lifetimes. */
internal class JdArtwork {
    private data class Caption(val text: String, val bounds: Rect)
    private data class Item(val icon: Bitmap, val slotWidth: Int, val labelPaint: Paint,
                            val label: Caption, val englishLabel: Caption)
    private data class Frame(val rowType: Class<*>, val width: Int, val height: Int,
                             val items: List<Item?>)
    private data class Badge(val bitmap: Bitmap, val bounds: RectF, val slotWidth: Int, val rowHeight: Int)
    private class Drawing {
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = RectF()
    }

    // Both the View and optical Surface can draw the published artwork. An
    // older frame keeps its bitmaps alive while the new frame is published.
    @Volatile private var frame: Frame? = null
    @Volatile private var badges: List<Badge?> = emptyList()
    private val drawing = ThreadLocal.withInitial(::Drawing)
    private var binding: JdNavigationBinding.Row? = null
    private var lastBadgeCaptureAt = 0L
    private val selectedIconFilter = BlendModeColorFilter(0xFFE02E24.toInt(), BlendMode.SRC_IN)
    private val lightIconFilter = BlendModeColorFilter(0xFF424242.toInt(), BlendMode.SRC_IN)
    private val darkIconFilter = BlendModeColorFilter(Color.WHITE, BlendMode.SRC_IN)
    val jdStaticIconsPopulated: Boolean get() = frame != null
    var artworkRefreshPending = false
        private set

    fun prepare(source: ViewGroup?, count: Int) {
        val row = JdNavigationBinding.resolve(source)?.takeIf { it.slots.size == count }
        val previous = frame
        if (row != null && previous != null &&
            (previous.rowType != row.view.javaClass || previous.width != row.view.width || previous.height != row.view.height)) {
            artworkRefreshPending = true
        }
        binding = row
    }

    fun accept(candidate: Bitmap, populate: Boolean): Boolean {
        if ((frame == null || artworkRefreshPending) && populate) {
            captureFrame(candidate)?.let {
                frame = it
                artworkRefreshPending = false
                lastBadgeCaptureAt = 0L
            }
        }
        if (frame == null) return false
        refreshBadges(candidate)
        return true
    }

    private fun slotWidth(candidate: Bitmap, source: ViewGroup, button: View): Int {
        val scale = candidate.width.toFloat() / source.width.coerceAtLeast(1)
        return ((button.right * scale).toInt() - (button.left * scale).toInt()).coerceAtLeast(0)
    }

    private fun captureFrame(candidate: Bitmap): Frame? {
        val row = binding ?: return null
        val source = row.view
        if (source.width <= 0 || source.height <= 0 || !row.isCurrent()) return null
        val items = arrayOfNulls<Item>(row.slots.size)
        val icons = arrayOfNulls<Bitmap>(row.slots.size)
        var published = false
        try {
            // Draw only the native icon View. Text and unread badges are its
            // siblings, so a transient hidden TextView cannot affect this cache.
            for (index in row.slots.indices) {
                if (index == JdNavigationBinding.HOME || index == JdNavigationBinding.BROWSE) continue
                val icon = row.slots[index].icon
                icons[index] = raster(icon.width, icon.height, icon::draw) ?: return null
            }
            icons[JdNavigationBinding.HOME] = captureHome(row, icons.filterNotNull()) ?: return null
            for (index in row.slots.indices) {
                val icon = icons[index] ?: continue
                val slot = row.slots[index]
                val width = slotWidth(candidate, source, slot.button)
                if (width <= 0) return null
                val title = slot.title?.trim()?.takeIf(String::isNotEmpty) ?: targetSpec.entryLabels[index]
                val paint = Paint(slot.label.paint).apply {
                    isAntiAlias = true
                    textAlign = Paint.Align.CENTER
                    textScaleX = 1f
                    style = Paint.Style.FILL
                }
                if (paint.textSize <= 0f) return null
                fun caption(text: String) = Caption(text, Rect().also { paint.getTextBounds(text, 0, text.length, it) })
                items[index] = Item(icon, width, paint, caption(title),
                    caption(NavigationLabelLocalization.toEnglish("jd", title)))
            }
            return Frame(source.javaClass, source.width, candidate.height, items.toList()).also { published = true }
        } finally {
            if (!published) icons.forEach { it?.recycle() }
        }
    }

    /** Keep the same native Home house asset used by the previous renderer. */
    private fun captureHome(row: JdNavigationBinding.Row, peerIcons: List<Bitmap>): Bitmap? {
        val view = row.slots[JdNavigationBinding.HOME].icon
        val drawable = defaultDrawable(view) ?: return raster(view.width, view.height, view::draw)
        val nativeBounds = row.slots.drop(1).filter { it.button.isShown }.mapNotNull { slot ->
            val bounds = defaultDrawable(slot.icon)?.let { field(it, "lastIconRect") } as? Rect
            bounds?.takeIf { it.width() in 1..slot.icon.width && it.height() in 1..slot.icon.height }
        }
        fun median(values: List<Int>) = values.sorted()[values.size / 2]
        val referenceWidth = median(if (nativeBounds.size >= 2) nativeBounds.map(Rect::width) else peerIcons.map(Bitmap::getWidth))
        val referenceHeight = median(if (nativeBounds.size >= 2) nativeBounds.map(Rect::height) else peerIcons.map(Bitmap::getHeight))
        val bitmap = (field(drawable, "bitmap") as? Bitmap)?.takeUnless(Bitmap::isRecycled)
        val width = bitmap?.width ?: drawable.intrinsicWidth.takeIf { it > 0 } ?: referenceWidth
        val height = bitmap?.height ?: drawable.intrinsicHeight.takeIf { it > 0 } ?: referenceHeight
        val fit = min(referenceWidth.toFloat() / width, referenceHeight.toFloat() / height)
        val fittedWidth = (width * fit).roundToInt().coerceAtLeast(1)
        val fittedHeight = (height * fit).roundToInt().coerceAtLeast(1)
        return raster(fittedWidth, fittedHeight) { canvas ->
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, fittedWidth, fittedHeight), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val previousBounds = Rect(drawable.bounds)
                try {
                    drawable.setBounds(0, 0, fittedWidth, fittedHeight)
                    drawable.draw(canvas)
                } finally {
                    drawable.bounds = previousBounds
                }
            }
        }
    }

    private fun raster(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap? {
        if (width <= 0 || height <= 0 || width.toLong() * height > 1_000_000) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var keep = false
        try {
            draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            for (y in 0 until height) for (x in 0 until width) {
                if (pixels[y * width + x] ushr 24 == 0) continue
                left = min(left, x)
                top = min(top, y)
                right = max(right, x + 1)
                bottom = max(bottom, y + 1)
            }
            if (right <= left || bottom <= top) return null
            return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top).also { keep = it === bitmap }
        } finally {
            if (!keep) bitmap.recycle()
        }
    }

    fun refreshBadges(candidate: Bitmap) {
        val row = binding ?: return
        val source = row.view
        if (source.width <= 0 || source.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastBadgeCaptureAt < BADGE_CAPTURE_INTERVAL_MS) return
        lastBadgeCaptureAt = now
        val scaleX = candidate.width.toFloat() / source.width
        val scaleY = candidate.height.toFloat() / source.height
        val origin = IntArray(2).also(source::getLocationInWindow)
        badges = row.slots.mapIndexed { index, slot ->
            val badge = slot.badge
            if (index < 2 || !badge.isShown || badge.width <= 0 || badge.height <= 0 || badge.alpha <= 0f) return@mapIndexed null
            val width = slotWidth(candidate, source, slot.button)
            if (width <= 0) return@mapIndexed null
            val position = IntArray(2).also(badge::getLocationInWindow)
            val left = (position[0] - origin[0]) * scaleX - (slot.button.left * scaleX).toInt()
            val top = (position[1] - origin[1]) * scaleY
            val bitmap = Bitmap.createBitmap(badge.width, badge.height, Bitmap.Config.ARGB_8888)
            badge.draw(Canvas(bitmap))
            Badge(bitmap, RectF(left, top, left + badge.width * scaleX, top + badge.height * scaleY), width, candidate.height)
        }
    }

    fun draw(canvas: Canvas, bar: AdapterNavigationFrame) {
        val current = frame ?: return
        if (bar.width <= 0 || bar.height <= 0 || bar.slotWidth <= 0f) return
        val badgeFrame = badges
        val resources = drawing.get()
        val itemScale = bar.iconScale * bar.extraScale * CONTENT_SCALE
        val centerY = bar.height / 2f
        val badgeCenterY = centerY + BADGE_OFFSET_Y_DP * bar.density
        for ((visualIndex, index) in bar.enabledIndices.withIndex()) {
            val item = current.items.getOrNull(index) ?: continue
            val centerX = 4f * bar.density + (visualIndex + 0.5f) * bar.slotWidth
            val selected = index == bar.selectedIndex
            val color = if (selected) 0xFFE02E24.toInt() else if (bar.darkGlass) Color.WHITE else 0xFF424242.toInt()
            val baseScale = min(bar.slotWidth / item.slotWidth, bar.height.toFloat() / current.height) * itemScale
            val label = if (bar.managerEnglishLabels) item.englishLabel else item.label
            val iconScale = baseScale * ICON_ENLARGEMENT
            val textScale = baseScale * CAPTION_SCALE
            val gap = if (bar.iconOnly) 0f else CAPTION_GAP_DP * bar.density
            val labelHeight = if (bar.iconOnly) 0f else label.bounds.height() * textScale
            val groupHeight = item.icon.height * iconScale + gap + labelHeight
            val groupWidth = max(item.icon.width * iconScale, if (bar.iconOnly) 0f else label.bounds.width() * textScale)
            val fit = min(1f, min(bar.height / groupHeight, bar.slotWidth / groupWidth))
            val iconWidth = item.icon.width * iconScale * fit
            val iconHeight = item.icon.height * iconScale * fit
            val top = centerY - groupHeight * fit / 2f
            resources.bounds.set(centerX - iconWidth / 2f, top, centerX + iconWidth / 2f, top + iconHeight)
            resources.iconPaint.colorFilter = if (selected) selectedIconFilter else if (bar.darkGlass) darkIconFilter else lightIconFilter
            resources.iconPaint.alpha = if (selected) 255 else 235
            canvas.drawBitmap(item.icon, null, resources.bounds, resources.iconPaint)
            if (!bar.iconOnly) {
                resources.labelPaint.set(item.labelPaint)
                resources.labelPaint.color = color
                resources.labelPaint.alpha = if (selected) 255 else 235
                resources.labelPaint.textSize = item.labelPaint.textSize * textScale * fit
                val baseline = top + iconHeight + (gap - label.bounds.top * textScale) * fit
                canvas.drawText(label.text, centerX, baseline, resources.labelPaint)
            }
            badgeFrame.getOrNull(index)?.let { badge ->
                val scale = min(bar.slotWidth / badge.slotWidth, bar.height.toFloat() / badge.rowHeight) * itemScale
                val left = centerX - badge.slotWidth * scale / 2f
                val originY = badgeCenterY - badge.rowHeight * scale / 2f
                resources.bounds.set(left + badge.bounds.left * scale, originY + badge.bounds.top * scale,
                    left + badge.bounds.right * scale, originY + badge.bounds.bottom * scale)
                resources.iconPaint.colorFilter = null
                resources.iconPaint.alpha = 255
                canvas.drawBitmap(badge.bitmap, null, resources.bounds, resources.iconPaint)
            }
        }
    }

    fun dispose() {
        frame = null
        badges = emptyList()
        binding = null
        artworkRefreshPending = false
        lastBadgeCaptureAt = 0L
        drawing.remove()
    }

    private fun defaultDrawable(view: View): Drawable? =
        (field(view, "defaultDrawable") as? Drawable) ?: (field(view, "defaultNormalDrawable") as? Drawable)

    private fun field(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) return runCatching { field.isAccessible = true; field.get(instance) }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private companion object {
        const val ICON_ENLARGEMENT = 1.2f
        const val CAPTION_SCALE = 0.9f
        const val CAPTION_GAP_DP = 2f
        const val CONTENT_SCALE = 1.16f
        const val BADGE_OFFSET_Y_DP = -4f
        const val BADGE_CAPTURE_INTERVAL_MS = 250L
    }
}
