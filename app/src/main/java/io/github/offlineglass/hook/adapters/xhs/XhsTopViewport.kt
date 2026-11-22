package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** Owns only the native home feed's old header reservation, never the controls. */
internal class XhsTopViewport {
    private val layouts = LinkedHashMap<View, ViewGroup.LayoutParams>()
    private data class Padding(val top: Int, val clip: Boolean)
    private val padding = LinkedHashMap<ViewGroup, Padding>()
    private var targets = emptyList<View>()
    private var lists = emptyList<ViewGroup>()
    private data class Constraints(val top: java.lang.reflect.Field, val below: java.lang.reflect.Field)
    private val constraints = HashMap<Class<*>, Constraints?>()
    fun discover(root: ViewGroup) {
        val found = ArrayList<View>()
        val scrolling = ArrayList<ViewGroup>()
        fun walk(view: View) {
            if (view is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay || view is io.github.offlineglass.hook.GlassHostLayout) return
            when (view.javaClass.name) {
                "com.xingin.xhs.homepage.explorefeed.smoothexplore.SmoothExploreView" ->
                    XhsTopBinding.find(view) { it.javaClass.name == "androidx.coordinatorlayout.widget.CoordinatorLayout" }
                        ?.let(found::add)
                "com.xingin.xhs.homepage.followfeed.page.feed.BiserialFollowView" -> found.add(view)
                "com.xingin.xhs.homepage.explorefeed.mainfeed.view.FeedRecyclerView",
                "com.xingin.xhs.homepage.followfeed.widgets.view.FollowFeedRecyclerView" ->
                    (view as? ViewGroup)?.let(scrolling::add)
            }
            if (view is ViewGroup && !XhsTopBinding.isRecycler(view))
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root); targets = found; lists = scrolling
    }
    fun update(headerBottom: Int) {
        for (view in targets) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            if (!layouts.containsKey(view)) {
                val copy = runCatching {
                    lp.javaClass.getConstructor(ViewGroup.LayoutParams::class.java).newInstance(lp) as ViewGroup.LayoutParams
                }.getOrNull() ?: if (lp is FrameLayout.LayoutParams) FrameLayout.LayoutParams(lp) else null
                if (copy == null) continue
                layouts[view] = copy
            }
            var changed = false
            if (lp.topMargin != 0) { lp.topMargin = 0; changed = true }
            // CoordinatorLayout lives inside SmoothExploreView's ConstraintLayout.
            val type = lp.javaClass
            if (!constraints.containsKey(type)) constraints[type] = runCatching {
                Constraints(type.getField("topToTop"), type.getField("topToBottom"))
            }.getOrNull()
            constraints[type]?.let { fields ->
                if (fields.top.getInt(lp) != 0) { fields.top.setInt(lp, 0); changed = true }
                if (fields.below.getInt(lp) != -1) { fields.below.setInt(lp, -1); changed = true }
            }
            if (changed) view.layoutParams = lp
        }
        for (list in lists) {
            val saved = padding.getOrPut(list) { Padding(list.paddingTop, list.clipToPadding) }
            val top = saved.top + headerBottom
            if (list.paddingTop != top) list.setPadding(list.paddingLeft, top, list.paddingRight, list.paddingBottom)
            if (list.clipToPadding) list.clipToPadding = false
        }
    }
    fun restore() {
        layouts.forEach { (v, lp) -> v.layoutParams = lp }; layouts.clear()
        padding.forEach { (v, p) -> v.setPadding(v.paddingLeft, p.top, v.paddingRight, v.paddingBottom); v.clipToPadding = p.clip }
        padding.clear()
    }
}
