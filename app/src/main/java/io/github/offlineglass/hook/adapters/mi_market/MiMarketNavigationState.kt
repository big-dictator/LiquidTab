package io.github.offlineglass.hook.adapters.mi_market

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.util.WeakHashMap

/** Xiaomi Market owns its dynamic tab list, artwork, selection and click routing. */
internal class MiMarketNavigationState(private val context: Context) : AppNavigationState {
    private var source: ViewGroup? = null
    private val defaults = WeakHashMap<View, android.graphics.drawable.Drawable>()
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        this.source = source
    }

    override fun refreshNavigationSource(host: View): Boolean {
        val tabs = tabViews()
        if (tabs.isEmpty()) return false
        val selected = selectedIndex().coerceIn(0, tabs.lastIndex)
        (host as? GlassHostLayout)?.updateAdapterSlotCount(tabs.size, selected)
        return true
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        if (source != null) this.source = source
        return selectedIndex().takeIf { it in 0 until slotCount }
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        val tabs = tabViews()
        if (tabs.isEmpty() || frame.width <= 0 || frame.height <= 0) return
        labelPaint.textSize = 11f * frame.scaledDensity
        tabs.forEachIndexed { index, tab ->
            val info = field(tab, "mTab")
            val title = (field(tab, "mTitleView") as? TextView)?.text?.toString()
                ?: info?.let { runCatching { XposedHelpers.callMethod(it, "getTitle") as? String }.getOrNull() }
                ?: ""
            val icon = (field(tab, "mIconView") as? ImageView)?.drawable
                ?: defaults[tab]
                ?: info?.let {
                    runCatching { XposedHelpers.callMethod(it, "getDefaultTabIconDrawble") as? android.graphics.drawable.Drawable }.getOrNull()
                }?.also { defaults[tab] = it }
            val centerX = 4f * frame.density + (index + 0.5f) * frame.slotWidth
            canvas.save()
            canvas.scale(frame.iconScale * frame.extraScale, frame.iconScale * frame.extraScale, centerX, frame.height / 2f)
            icon?.let {
                val oldBounds = Rect(it.bounds)
                val oldFilter = it.colorFilter
                val width = 30f * 1.1f * frame.density
                val height = width * it.intrinsicHeight.coerceAtLeast(1) / it.intrinsicWidth.coerceAtLeast(1)
                val cy = 21.5f * frame.density
                it.setBounds((centerX - width / 2).toInt(), (cy - height / 2).toInt(),
                    (centerX + width / 2).toInt(), (cy + height / 2).toInt())
                if (index != frame.selectedIndex && frame.darkGlass) {
                    it.colorFilter = BlendModeColorFilter(Color.WHITE, BlendMode.SRC_IN)
                }
                try { it.draw(canvas) } finally { it.bounds = oldBounds; it.colorFilter = oldFilter }
            }
            labelPaint.color = if (index == frame.selectedIndex) {
                if (frame.darkGlass) 0xFFFF8469.toInt() else 0xFFFF6644.toInt()
            } else if (frame.darkGlass) Color.WHITE else 0xB3000000.toInt()
            canvas.drawText(title, centerX, 50f * frame.density, labelPaint)
            drawBadge(canvas, tab, centerX, 21.5f * frame.density, 30f * frame.density, frame)
            canvas.restore()
        }
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (source != null) this.source = source
        if (tabViews().getOrNull(index)?.performClick() == true) return true
        val bottom = bottomTab() ?: return false
        return runCatching { XposedHelpers.callMethod(bottom, "show", index, -1); true }.getOrDefault(false)
    }

    private fun drawBadge(canvas: Canvas, item: View, x: Float, y: Float, width: Float, frame: AdapterNavigationFrame) {
        val number = runCatching { (XposedHelpers.callMethod(item, "getNumber") as? Number)?.toInt() ?: 0 }.getOrDefault(0)
        if (number <= 0) return
        val text = if (number > 99) "99+" else number.toString()
        val h = 14f * frame.density
        badgeTextPaint.textSize = 8.5f * frame.scaledDensity
        val w = maxOf(h, badgeTextPaint.measureText(text) + 6f * frame.density)
        val cx = x + width * 0.38f
        val cy = y - 9.5f * frame.density
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, badgePaint)
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, badgeTextPaint)
    }

    private fun selectedIndex(): Int = (bottomTab()?.let { field(it, "mCurrentTabIndex") } as? Number)?.toInt() ?: 0

    private fun tabViews(): List<View> {
        val bottom = bottomTab() ?: return emptyList()
        (field(bottom, "mTabViews") as? List<*>)?.filterIsInstance<View>()?.takeIf { it.isNotEmpty() }?.let { return it }
        val row = field(bottom, "tabViewsLayout") as? ViewGroup ?: return emptyList()
        return (0 until row.childCount).map(row::getChildAt)
            .filter { it.javaClass.name == "com.xiaomi.market.widget.TabView" }
    }

    private fun bottomTab(): ViewGroup? {
        val root = source ?: return null
        val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view.javaClass.name == "com.xiaomi.market.widget.BottomTabLayout") return view as? ViewGroup
            if (view is ViewGroup && depth < 8) for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i) to depth + 1
        }
        var parent: View? = root
        repeat(6) {
            if (parent?.javaClass?.name == "com.xiaomi.market.widget.BottomTabLayout") return parent as? ViewGroup
            parent = parent?.parent as? View
        }
        return null
    }

    private fun field(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let { it.isAccessible = true; return it.get(instance) }
            type = type.superclass
        }
        return null
    }

    private companion object { const val BADGE_COLOR = 0xFFFF3B30.toInt() }
}
