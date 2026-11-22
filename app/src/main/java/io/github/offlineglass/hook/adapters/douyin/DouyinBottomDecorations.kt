package io.github.offlineglass.hook.adapters.douyin

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Only hairlines adjoining the verified native navigation hierarchy. */
internal object DouyinBottomDecorations {
    private data class Owned(val background: Drawable, val root: WeakReference<View>)
    private val cleared = WeakHashMap<View, Owned>()

    fun suppress(navigation: ViewGroup) {
        var ancestor = navigation.parent as? View
        var container: View? = null
        repeat(8) {
            val current = ancestor ?: return@repeat
            if (current.javaClass.name ==
                "com.ss.android.ugc.aweme.homepage.tab.bottom.ui.tabbar.container.MainBottomTabContainer") container = current
            ancestor = current.parent as? View
        }
        // Scope is proved by the native container owning this navigation row.
        val bottom = container ?: return
        clearHairlineAbove(navigation)
        clearHairlineAbove(bottom)
    }

    private fun clearHairlineAbove(anchor: View) {
        val parent = anchor.parent as? ViewGroup ?: return
        val tolerance = (2f * anchor.resources.displayMetrics.density).coerceAtLeast(1f)
        for (index in 0 until parent.childCount) {
            val view = parent.getChildAt(index)
            val decoration = view.javaClass == View::class.java || view.javaClass.name ==
                "com.ss.android.ugc.aweme.feed.ui.visibility.VisibilityControlledView"
            if (view === anchor || !decoration || view.isClickable || view.isFocusable ||
                !view.contentDescription.isNullOrEmpty() || view.height <= 0 || view.height > tolerance ||
                view.width < anchor.width * .95f || abs(view.bottom-anchor.top) > tolerance) continue
            val background = view.background ?: continue
            cleared[view] = Owned(background, WeakReference(view.rootView))
            // Native page transitions may restore alpha. Remove only the
            // actual decorative pixels, retaining dimensions and visibility.
            view.background = null
        }
    }

    fun restore(root: View?) {
        if (root == null) return
        val iterator = cleared.entries.iterator()
        while (iterator.hasNext()) {
            val (view, owned) = iterator.next()
            if (owned.root.get() !== root) continue
            if (view.background == null) view.background = owned.background
            iterator.remove()
        }
    }
}
