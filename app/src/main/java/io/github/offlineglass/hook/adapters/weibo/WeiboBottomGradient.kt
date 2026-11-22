package io.github.offlineglass.hook.adapters.weibo

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.home_glass.HomeEdgeColorGradient

/** Same color fade as Bilibili, below navigation and independent of its optics. */
internal class WeiboBottomGradient {
    private class Layer(context: android.content.Context) : View(context), AdapterOwnedOverlay
    private var layer: Layer? = null
    private val gradient = HomeEdgeColorGradient(top = false)
    private val hostPosition = IntArray(2)
    private val parentPosition = IntArray(2)
    fun update(host: View, enabled: Boolean, dark: Boolean) {
        if (!enabled) { clear(); return }
        val parent = host.parent as? FrameLayout ?: return
        if (layer?.parent !== parent) {
            clear()
            layer = Layer(host.context).apply {
                setForceDarkAllowed(false)
                background = gradient; isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                parent.addView(this, parent.indexOfChild(host).coerceAtLeast(0), FrameLayout.LayoutParams(1, 1))
            }
        }
        val view = layer ?: return
        view.visibility = if (host.isShown) View.VISIBLE else View.GONE
        gradient.setDark(dark)
        host.getLocationInWindow(hostPosition); parent.getLocationInWindow(parentPosition)
        val top = (hostPosition[1] - parentPosition[1]).coerceIn(0, parent.height)
        val height = (parent.height - top).coerceAtLeast(1)
        val lp = view.layoutParams as FrameLayout.LayoutParams
        if (lp.width != parent.width || lp.height != height || lp.topMargin != top) {
            lp.width = parent.width; lp.height = height; lp.topMargin = top; view.layoutParams = lp
        }
    }
    fun clear() {
        val old = layer ?: return
        layer = null
        old.visibility = View.GONE
        val parent = old.parent as? ViewGroup ?: return
        // detach dispatch iterates the parent's child array; never mutate it inline.
        parent.post { if (old.parent === parent) parent.removeView(old) }
    }
}
