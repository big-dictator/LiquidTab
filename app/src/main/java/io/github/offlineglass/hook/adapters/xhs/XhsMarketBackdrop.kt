package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/** Keep the adjacent market page's sliding background images inside their own viewport. */
internal class XhsMarketBackdrop(private val homeTabs: View) {
    private var background: View? = null
    private var originalClip: Rect? = null
    private var appliedClip: Rect? = null
    var discovered = false
        private set
    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        updateClip(view)
    }

    fun discover() {
        discovered = true
        val candidate = findBackground()
        if (candidate !== background) {
            releaseBackground()
            background = candidate
            candidate?.addOnLayoutChangeListener(layoutListener)
        }
        candidate?.let(::updateClip)
    }

    private fun findBackground(): View? {
        val homePage = homeTabs.parent as? ViewGroup ?: return null
        val pager = homePage.parent as? ViewGroup ?: return null
        if (pager.javaClass.name != "com.xingin.redview.viewpage.ExploreScrollableViewPager") return null

        var match: View? = null
        for (index in 0 until pager.childCount) {
            val page = pager.getChildAt(index) as? ViewGroup ?: continue
            if (page === homePage) continue
            for (child in 0 until page.childCount) {
                val view = page.getChildAt(child) as? ViewGroup ?: continue
                if (view.javaClass.name != "com.xingin.commercial.shop.widget.MartBackgroundView" ||
                    view.isClickable || view.childCount < 2 ||
                    (0 until view.childCount).any { view.getChildAt(it) !is ImageView }) continue
                if (match != null) return null
                match = view
            }
        }
        return match
    }

    private fun updateClip(view: View) {
        if (view.width <= 0 || view.height <= 0) return
        val current = view.clipBounds
        if (current != appliedClip) {
            originalClip = current
            appliedClip = null
        }
        val bounds = Rect(0, 0, view.width, view.height)
        originalClip?.let {
            if (!bounds.intersect(it)) return
        }
        if (current == bounds) return
        // clipChildren constrains the individual image nodes. An explicit
        // container clip also contains images translated by an entire page.
        view.clipBounds = bounds
        appliedClip = bounds
    }

    fun restore() {
        releaseBackground()
        discovered = false
    }

    private fun releaseBackground() {
        background?.let { view ->
            view.removeOnLayoutChangeListener(layoutListener)
            if (appliedClip != null && view.clipBounds == appliedClip) view.clipBounds = originalClip
        }
        background = null
        originalClip = null
        appliedClip = null
    }
}
