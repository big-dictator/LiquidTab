package io.github.offlineglass.hook.adapters.xhs

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Remove only the home pager's obsolete native bottom-tab reservation. */
internal class XhsHomeViewport {
    private var pager: View? = null
    private var root: View? = null
    private var scannedAt = Long.MIN_VALUE
    private val margins = WeakHashMap<View, Int>()

    fun update(windowRoot: View) {
        if (root !== windowRoot) { restore(); root = windowRoot }
        if (pager?.isAttachedToWindow != true) {
            val now = SystemClock.uptimeMillis()
            if (scannedAt != Long.MIN_VALUE && now - scannedAt < 500L) return
            scannedAt = now
            val content = XhsTopBinding.find(windowRoot) {
                it.javaClass.name == "com.xingin.xhs.homepage.content.ContentView"
            } ?: return
            pager = XhsTopBinding.find(content) {
                it.javaClass.name == "com.xingin.redview.viewpage.ExploreScrollableViewPager"
            }
        }
        val view = pager ?: return
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        margins.getOrPut(view) { params.bottomMargin }
        // Native foreground recovery may reapply its layout. Recheck the cached
        // target before drawing; writing only on change avoids a layout loop.
        if (params.bottomMargin != 0) {
            params.bottomMargin = 0
            view.layoutParams = params
        }
    }

    fun restore() {
        margins.forEach { (view, original) ->
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@forEach
            if (params.bottomMargin == 0 && original != 0) {
                params.bottomMargin = original; view.layoutParams = params
            }
        }
        margins.clear(); pager = null; root = null; scannedAt = Long.MIN_VALUE
    }
}
