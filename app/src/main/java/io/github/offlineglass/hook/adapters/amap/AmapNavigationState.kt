package io.github.offlineglass.hook.adapters.amap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import kotlin.math.max

/** Amap's native LiteTabBar assets and AI-chat page gate, scoped to one glass host. */
internal class AmapNavigationState(private val context: Context) : AppNavigationState {
    private val icons = arrayOfNulls<Bitmap>(8)
    private val labels = arrayOfNulls<String>(8)
    private var populated = false
    private var populateAttempted = false
    private var aiChatCacheAt = 0L
    private var aiChatCached = false
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    override val readyForDrawing: Boolean get() = populated

    private fun cells(source: ViewGroup): List<ViewGroup> {
        val result = ArrayList<ViewGroup>()
        val stack = ArrayDeque<View>()
        stack += source
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view is ViewGroup && view.javaClass.name.endsWith("TabItemLayoutV2")) {
                result += view
            } else if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i)
            }
        }
        val location = IntArray(2)
        return result.sortedBy { it.getLocationInWindow(location); location[0] }
    }

    private fun cellIcon(cell: ViewGroup): Drawable? {
        val tabImgId = runCatching {
            context.resources.getIdentifier("tab_img", "id", context.packageName)
        }.getOrDefault(0)
        if (tabImgId != 0) {
            (cell.findViewById<View>(tabImgId) as? ImageView)?.drawable?.let { return it }
        }
        var best: ImageView? = null
        var bestArea = 0
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += cell to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is ImageView && depth <= 4) {
                val area = view.width * view.height
                if (area > bestArea && view.id != 0) {
                    bestArea = area
                    best = view
                }
            }
            if (view is ViewGroup && depth < 5) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
            }
        }
        return best?.drawable?.takeIf { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 }
            ?: best?.drawable
    }

    private fun cellLabel(cell: ViewGroup): String? {
        for (name in listOf("tab_name_v2", "tab_name")) {
            val id = runCatching {
                context.resources.getIdentifier(name, "id", context.packageName)
            }.getOrDefault(0)
            if (id != 0) {
                (cell.findViewById<View>(id) as? TextView)?.text?.toString()
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += cell to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is TextView && depth <= 4) {
                view.text?.toString()?.trim()?.takeIf { it.length in 1..6 }?.let { return it }
            }
            if (view is ViewGroup && depth < 5) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
            }
        }
        return null
    }

    private fun iconBitmap(drawable: Drawable): Bitmap? {
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        }.onFailure { XposedBridge.log("[OfflineGlass][Amap] icon bitmap: $it") }.getOrNull()
    }

    private fun populate(source: ViewGroup): Boolean {
        if (populated) return true
        val nativeCells = cells(source)
        if (nativeCells.isEmpty()) return false
        var filled = 0
        for (index in nativeCells.indices) {
            if (index >= icons.size) break
            val bitmap = cellIcon(nativeCells[index])?.let(::iconBitmap)
            if (bitmap != null) {
                icons[index]?.takeUnless(Bitmap::isRecycled)?.recycle()
                icons[index] = bitmap
                filled++
            }
            labels[index] = cellLabel(nativeCells[index])
        }
        populated = filled > 0
        if (populated) runCatching { if (source.alpha != 0f) source.alpha = 0f }
        XposedBridge.log("[OfflineGlass][Amap] populated icons: $filled/${nativeCells.size}")
        return populated
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        if (populated || populateAttempted || source == null || source.width <= 0 || source.height <= 0) return
        populateAttempted = true
        source.postDelayed({ if (!populated && source.width > 0) populate(source) }, 280L)
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (!populated || frame.slotCount <= 0 || frame.width <= 0 || frame.height <= 0) return
        with(frame) {
            val horizontalPadding = 4f * density
            val unselectedColor = if (darkGlass) 0xFFE6E6E6.toInt() else 0xFF5F6672.toInt()
            val itemScale = iconScale * extraScale
            labelPaint.textSize = 11f * scaledDensity
            val fm = labelPaint.fontMetrics
            val labelHeight = fm.descent - fm.ascent
            val viewport = 31.68f * density
            val gap = 3f * density
            val groupTop = height / 2f - (viewport + gap + labelHeight) / 2f
            val iconCenterY = groupTop + viewport / 2f
            val labelBaseline = groupTop + viewport + gap - fm.ascent
            for ((visualIndex, index) in enabledIndices.asReversed().withIndex()) {
                val selected = index == selectedIndex
                val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth
                val bitmap = icons.getOrNull(index)?.takeUnless(Bitmap::isRecycled) ?: continue
                val label = labels.getOrNull(index)
                val color = if (selected) accentColor else unselectedColor
                val w = bitmap.width.toFloat().coerceAtLeast(1f)
                val h = bitmap.height.toFloat().coerceAtLeast(1f)
                val scale = (viewport / max(w, h)).coerceAtMost(1.15f)
                val dw = w * scale
                val dh = h * scale
                canvas.save()
                canvas.scale(itemScale, itemScale, centerX, iconCenterY)
                iconPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
                iconPaint.alpha = if (selected) 255 else 235
                canvas.drawBitmap(bitmap, null, RectF(centerX - dw / 2f, iconCenterY - dh / 2f,
                    centerX + dw / 2f, iconCenterY + dh / 2f), iconPaint)
                if (label != null) {
                    labelPaint.color = color
                    labelPaint.alpha = if (selected) 255 else 235
                    canvas.drawText(label, centerX, labelBaseline, labelPaint)
                }
                canvas.restore()
            }
        }
        iconPaint.colorFilter = null
        iconPaint.alpha = 255
        labelPaint.alpha = 255
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        source ?: return false
        val nativeCells = cells(source)
        if (nativeCells.size != slotCount) return false
        val tab = nativeCells.getOrNull(index)?.takeIf { it.isAttachedToWindow } ?: return false
        return tab.performClick()
    }

    override fun pageAllowsNavigation(root: View?): Boolean = !isAiChatActive(root)

    private fun isAiChatActive(root: View?): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - aiChatCacheAt < 180L) return aiChatCached
        aiChatCacheAt = now
        root ?: return aiChatCached
        val stack = ArrayDeque<View>()
        stack += root
        var found = false
        var scanned = 0
        while (stack.isNotEmpty() && !found) {
            val view = stack.removeLast()
            val cls = view.javaClass.name
            if (cls.contains("ainative") &&
                (cls.contains("Chat") || cls.contains("AIChat") || cls.contains("AJXContainer"))) {
                found = view.isShown
            }
            if (!found && view is ViewGroup && scanned++ < 4096) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i)
            }
        }
        aiChatCached = found
        return found
    }

    override fun dispose() {
        icons.forEach { it?.takeUnless(Bitmap::isRecycled)?.recycle() }
        icons.fill(null)
        labels.fill(null)
        populated = false
        populateAttempted = false
    }
}
