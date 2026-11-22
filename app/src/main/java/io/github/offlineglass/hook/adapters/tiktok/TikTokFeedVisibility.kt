package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.Rect
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Visibility of the semantically verified feed viewport, never a screen-wide heuristic. */
internal object TikTokFeedVisibility {
    private val feeds = WeakHashMap<View, WeakReference<View>>()
    private val location = IntArray(2)
    private val windowLocation = IntArray(2)
    private val visible = Rect()
    fun bind(window: View, feed: View) {
        var parent: View? = feed
        while (parent != null && parent !== window) {
            if (parent.javaClass.simpleName == "VideoSeekBarMaskView") {
                feeds[window] = WeakReference(parent)
                return
            }
            parent = parent.parent as? View
        }
        // A channel pager moving alone does not imply leaving the home page.
        feeds.remove(window)
    }
    fun isVisible(window: View?): Boolean? {
        window ?: return null
        val feed = feeds[window]?.get() ?: return null
        if (!feed.isAttachedToWindow || feed.rootView !== window) { feeds.remove(window); return null }
        if (!feed.isShown || !feed.getGlobalVisibleRect(visible)) return false
        var ancestor: View? = feed
        while (ancestor != null) {
            if (ancestor.alpha <= .01f) return false
            ancestor = ancestor.parent as? View
        }
        feed.getLocationInWindow(location)
        window.getLocationInWindow(windowLocation)
        // Vertical feed swipes move cells, not this viewport. Horizontal home
        // paging moves the viewport itself, including author-page transitions.
        return kotlin.math.abs(location[0]-windowLocation[0]) <= 1 && visible.width() >= feed.width-2
    }
}
