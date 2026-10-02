package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.os.SystemClock

/** Projects native artwork into glass slots, independent of native row insets. */
internal class YouTubeTabsRenderer {
    private data class Tab(val group: ViewGroup, val icon: View?, val label: TextView?,
                           val badges: List<View>, val childCount: Int)
    private var cachedSource: ViewGroup? = null
    private var cachedCount = 0
    private var cachedTabs: List<Tab> = emptyList()
    private var nextRefresh = 0L
    private val origin = IntArray(2)
    private val position = IntArray(2)

    fun clear() {
        cachedSource = null
        cachedTabs = emptyList()
        nextRefresh = 0L
    }
    private fun name(view: View) = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    private fun find(root: View, id: String): View? {
        if (name(root) == id) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) {
            find(root.getChildAt(index), id)?.let { return it }
        }
        return null
    }

    private fun tabs(source: ViewGroup, count: Int): List<ViewGroup> {
        val queue = ArrayDeque<ViewGroup>()
        queue += source
        while (queue.isNotEmpty()) {
            val group = queue.removeFirst()
            val children = (0 until group.childCount).map(group::getChildAt)
            if (children.size == count && children.all {
                it is ViewGroup && (find(it, "thumbnail_layout") != null || find(it, "image") != null)
            }) return children.filterIsInstance<ViewGroup>()
            children.filterIsInstance<ViewGroup>().forEach(queue::addLast)
        }
        return emptyList()
    }

    private fun tab(source: ViewGroup, count: Int, index: Int): Tab? {
        val now = SystemClock.uptimeMillis()
        if (cachedSource !== source || cachedCount != count || now >= nextRefresh ||
            cachedTabs.any { it.group.parent == null || it.group.childCount != it.childCount ||
                (it.icon != null && it.icon.parent == null) || (it.label != null && it.label.parent == null) }) {
            cachedSource = source
            cachedCount = count
            cachedTabs = tabs(source, count).map { group ->
                Tab(group, find(group, "thumbnail_layout") ?: find(group, "image"),
                    find(group, "text") as? TextView,
                    listOfNotNull(find(group, "new_content_dot"), find(group, "new_content_count")),
                    group.childCount)
            }
            // Recheck lazy badge stubs and native row replacements; never
            // rescan the entire row for every slot or optical pass.
            nextRefresh = now + 250L
        }
        return cachedTabs.getOrNull(index)
    }

    private fun draw(canvas: Canvas, view: View, x: Float, y: Float, scale: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        // Direct View.draw does not apply the View's RenderNode alpha.
        // Avoid invalidating native layout/parents with temporary alpha writes.
        try { view.draw(canvas) } finally { canvas.restore() }
    }

    fun drawSlot(canvas: Canvas, source: ViewGroup, count: Int, index: Int,
                 centerX: Float, centerY: Float, iconScale: Float, iconOnly: Boolean) {
        val tab = tab(source, count, index) ?: return
        val icon = tab.icon
            ?.takeIf { it.width > 0 && it.height > 0 }
        val label = tab.label
            ?.takeIf { !iconOnly && it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        val scale = 1.08f * iconScale
        val iconHeight = (icon?.height ?: 0) * scale
        val labelHeight = (label?.height ?: 0) * scale
        val gap = if (icon != null && label != null) {
            icon.getLocationInWindow(origin)
            label.getLocationInWindow(position)
            (position[1] - origin[1] - icon.height).coerceAtLeast(0).toFloat()
        } else 0f
        val top = centerY - (iconHeight + gap + labelHeight) / 2f
        icon?.let { draw(canvas, it, centerX - it.width * scale / 2f, top, scale) }
        label?.let { draw(canvas, it, centerX - it.width * scale / 2f, top + iconHeight + gap, scale) }
        if (icon != null) for (badge in tab.badges) {
            if (badge.visibility != View.VISIBLE || badge.width <= 0) continue
            icon.getLocationInWindow(origin)
            badge.getLocationInWindow(position)
            draw(canvas, badge, centerX - icon.width * scale / 2f + (position[0] - origin[0]) * scale,
                top + (position[1] - origin[1]) * scale, scale)
        }
    }
}
