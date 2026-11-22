package io.github.offlineglass.hook.adapters.mi_health

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Title shadow/glow only: retain native weight, font, size and layout metrics. */
internal class MiHealthTitleStyle {
    private data class Original(val radius: Float, val dx: Float, val dy: Float, val color: Int)
    private val originals = WeakHashMap<TextView, Original>()
    private var page = -1
    private var header: WeakReference<ViewGroup>? = null
    private var titles = emptyList<WeakReference<TextView>>()
    private var lastProbe = 0L

    fun update(selected: Int, nativeHeader: ViewGroup?, reactTitle: TextView?, dark: Boolean) {
        if (selected !in 0..2) return
        val now = android.os.SystemClock.uptimeMillis()
        if (selected != page || header?.get() !== nativeHeader || now - lastProbe >= 600L) {
            page = selected; header = nativeHeader?.let(::WeakReference); lastProbe = now
            val found = if (selected == 1 && reactTitle != null) listOf(reactTitle) else {
                val scope = nativeHeader?.let { root ->
                    // fragment_data_v2 and device_tab_parent_v4 both use
                    // HomeHeaderTabTitleView / layout_home_header_tab.
                    if (selected == 0 || selected == 2) byId(root, "title_list") else byId(root, "header_view_tv_title")
                }
                when (scope) {
                    is TextView -> listOf(scope)
                    is ViewGroup -> {
                        val result = ArrayList<TextView>()
                        val stack = ArrayDeque<View>(); stack += scope
                        var count = 0
                        while (stack.isNotEmpty() && count++ < 32) {
                            val view = stack.removeLast()
                            if (view is TextView) result += view
                            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
                        }
                        if (stack.isEmpty()) result else emptyList()
                    }
                    else -> emptyList()
                }
            }
            titles = found.map(::WeakReference)
        }
        titles.forEach { reference ->
            val title = reference.get()?.takeIf { it.isAttachedToWindow } ?: return@forEach
            originals.putIfAbsent(title, Original(title.shadowRadius, title.shadowDx, title.shadowDy, title.shadowColor))
            val density = title.resources.displayMetrics.density
            val radius = 2f * density
            val dy = if (dark) 1f * density else 0f
            val color = if (dark) Color.argb(100, 0, 0, 0) else Color.argb(150, 255, 255, 255)
            var changed = false
            if (title.shadowColor != color || title.shadowRadius != radius || title.shadowDx != 0f || title.shadowDy != dy) {
                title.setShadowLayer(radius, 0f, dy, color); changed = true
            }
            if (changed) title.invalidate()
        }
    }

    private fun byId(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", root.context.packageName)
        return if (id != 0) root.findViewById(id) else null
    }

    fun dispose() {
        originals.forEach { (title, original) ->
            title.setShadowLayer(original.radius, original.dx, original.dy, original.color)
            title.invalidate()
        }
        originals.clear(); titles = emptyList(); header = null; page = -1; lastProbe = 0L
    }
}
