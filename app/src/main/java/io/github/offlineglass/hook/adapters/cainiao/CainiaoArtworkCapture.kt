package io.github.offlineglass.hook.adapters.cainiao

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Draw leaves directly: hidden native ancestors and their clipping cannot erase artwork. */
internal object CainiaoArtworkCapture {
    private const val ENLARGEMENT = 1.2f
    private val labelIds = setOf("home_page_nav_tv_name", "tv_name")
    // Home's selected artwork is a 52dp two-state sprite clipped by a 25dp
    // container with a separate blue background. Capture that container,
    // including the native child's translation, rather than scaling the sprite.
    private val iconIds = listOf("tabbar_container", "nav_tab_icon_static", "home_page_nav_nav_tab_icon_biz",
        "home_page_nav_nav_tab_icon_animation_v81012", "home_page_nav_nav_tab_icon_animation",
        "home_page_nav_nav_tab_icon", "nav_tab_icon")
    private val badgeIds = setOf("number_red_dots_textView", "redpoint_imageView")

    private data class Tab(val icon: View, val label: TextView, val badges: List<View>)

    private fun descendants(root: View): List<View> {
        val result = ArrayList<View>()
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return result
    }

    fun capture(source: ViewGroup, target: Bitmap): Boolean {
        val all = descendants(source)
        val labels = all.filterIsInstance<TextView>().filter { it.resourceName() in labelIds }
        if (labels.size != 5) return false
        val tabs = labels.map { label ->
            var parent = label.parent as? ViewGroup
            var tab: Tab? = null
            while (parent != null && parent !== source) {
                val children = descendants(parent)
                if (children.count { it is TextView && it.resourceName() in labelIds } != 1) break
                val icon = iconIds.firstNotNullOfOrNull { id -> children.firstOrNull {
                    it.resourceName() == id && it.width > 0 && it.height > 0
                } }
                if (icon != null) {
                    tab = Tab(icon, label, children.filter { it.resourceName() in badgeIds })
                    break
                }
                parent = parent.parent as? ViewGroup
            }
            tab ?: return false
        }.sortedBy { it.label.screenPosition()[0] }
        if (tabs.any { it.label.width <= 0 || it.label.height <= 0 }) return false
        val canvas = Canvas(target)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, BlendMode.CLEAR)
        val slotWidth = target.width / 5f
        val origin = source.screenPosition()
        val sourceTop = origin[1]
        tabs.forEachIndexed { index, tab ->
            val icon = tab.icon
            val label = tab.label
            val oldIcon = icon.screenPosition()
            val oldLabel = label.screenPosition()
            val baseIconHeight = icon.height * icon.scaleY
            val baseLabelHeight = label.height * label.scaleY
            val gap = (oldLabel[1] - oldIcon[1] - baseIconHeight).coerceAtLeast(0f)
            val iconHeight = baseIconHeight * ENLARGEMENT
            val labelHeight = baseLabelHeight * ENLARGEMENT
            val center = (index + 0.5f) * slotWidth
            val top = (target.height - iconHeight - gap - labelHeight) / 2f
            canvas.save()
            canvas.clipRect(index * slotWidth, 0f, (index + 1) * slotWidth, target.height.toFloat())
            drawLeaf(canvas, icon, center, top, ENLARGEMENT)
            drawLeaf(canvas, label, center, top + iconHeight + gap, ENLARGEMENT)
            // Badge size and colour stay native; anchor follows the enlarged icon's top/right.
            val newIconRight = center + icon.width * icon.scaleX * ENLARGEMENT / 2f
            val dx = newIconRight - (oldIcon[0] - origin[0] + icon.width * icon.scaleX)
            val dy = top - (oldIcon[1] - sourceTop)
            tab.badges.forEach { badge ->
                val pos = badge.screenPosition()
                drawLeaf(canvas, badge, pos[0] - origin[0] + dx + badge.width * badge.scaleX / 2f,
                    pos[1] - sourceTop + dy, 1f)
            }
            canvas.restore()
        }
        return true
    }

    private fun View.screenPosition(): IntArray = IntArray(2).also(::getLocationOnScreen)

    private fun drawLeaf(canvas: Canvas, view: View, centerX: Float, top: Float, scale: Float) {
        if (view.alpha <= 0f || view.width <= 0 || view.height <= 0) return
        canvas.save()
        canvas.translate(centerX - view.width * view.scaleX * scale / 2f, top)
        canvas.scale(view.scaleX * scale, view.scaleY * scale)
        val layer = canvas.saveLayerAlpha(0f, 0f, view.width.toFloat(), view.height.toFloat(),
            (view.alpha * 255).toInt().coerceIn(0, 255))
        view.draw(canvas)
        canvas.restoreToCount(layer)
        canvas.restore()
    }
}
