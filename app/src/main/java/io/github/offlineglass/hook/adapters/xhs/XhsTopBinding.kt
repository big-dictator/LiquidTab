package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Target-local semantic/structural lookup; numeric IDs are hints, never identity. */
internal object XhsTopBinding {
    private val recyclerTypes = java.util.WeakHashMap<Class<*>, Boolean>()
    fun isRecycler(view: View): Boolean = recyclerTypes.getOrPut(view.javaClass) {
        generateSequence(view.javaClass as Class<*>?) { it.superclass }.any {
            it.name == "androidx.recyclerview.widget.RecyclerView" || it.name == "android.support.v7.widget.RecyclerView"
        }
    }
    fun find(root: View, matches: (View) -> Boolean): View? {
        if (root is AdapterOwnedOverlay || root is GlassHostLayout) return null
        if (matches(root)) return root
        if (isRecycler(root)) return null
        if (root is ViewGroup) for (i in 0 until root.childCount)
            find(root.getChildAt(i), matches)?.let { return it }
        return null
    }

    fun headerAction(tabs: View, right: Boolean, hint: Int): View? {
        val position = IntArray(2)
        tabs.getLocationInWindow(position)
        val tabX = position[0]; val tabY = position[1]
        val density = tabs.resources.displayMetrics.density
        fun valid(view: View): Boolean {
            if (view !is ImageView || view.width <= 0 || view.height <= 0) return false
            view.getLocationInWindow(position)
            return kotlin.math.abs(position[1] + view.height / 2 - tabY - tabs.height / 2) < 32 * density &&
                if (right) position[0] >= tabX + tabs.width else position[0] + view.width <= tabX
        }
        // Ascend only the home header; never borrow a search icon from the feed.
        var parent = tabs.parent as? ViewGroup
        repeat(4) {
            val scope = parent ?: return null
            scope.findViewById<View>(hint)?.takeIf(::valid)?.let { return it }
            find(scope) { valid(it) && it.hasOnClickListeners() }?.let { return it }
            parent = scope.parent as? ViewGroup
        }
        return null
    }

    fun channelRow(root: View): View? = find(root) {
        it.javaClass.name == "com.xingin.xhs.homepage.explorefeed.smoothexplore.SmoothExploreView"
    }?.let { smooth ->
        find(smooth) { it.javaClass.name == "com.xingin.matrix.widgets.NewTabLayout" }?.parent as? View
    }

    fun sheet(root: ViewGroup, category: View?): ViewGroup? {
        if (category == null || !category.isShown) return null
        var child: View = category
        while (child.parent is View && child.parent !== root) child = child.parent as View
        return (child as? ViewGroup)?.takeIf { it !== root && it.parent === root }
    }

    fun spacer(sheet: ViewGroup?): View? = sheet?.let {
        // Verified native sheet: LinearLayout(spacer View, FrameLayout(category)).
        if (it.childCount >= 2) it.getChildAt(0).takeIf { child ->
            child.javaClass == View::class.java && child.hasOnClickListeners()
        } else null
    }
}
