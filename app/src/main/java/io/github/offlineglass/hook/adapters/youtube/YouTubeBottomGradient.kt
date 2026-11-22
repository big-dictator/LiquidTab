package io.github.offlineglass.hook.adapters.youtube

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.home_glass.HomeEdgeColorGradient

/** The same cached tint as Bilibili; no capture, blur, timer or input ownership. */
internal class YouTubeBottomGradient(private val host: GlassHostLayout) {
    private class Veil(host: View) : View(host.context), AdapterOwnedOverlay
    private val gradient = HomeEdgeColorGradient(top = false)
    private var veil: Veil? = null
    private val visibleBounds = android.graphics.Rect()

    fun update(pageAllowed: Boolean) {
        val parent = host.parent as? FrameLayout ?: return
        val visible = pageAllowed && host.config.enabled && !host.config.classicNavigation &&
            host.visibility == View.VISIBLE && host.width > 0 && host.height > 0
        var view = veil
        if (view == null && !visible) return
        if (view?.parent !== parent) {
            release()
            view = Veil(host).apply {
                background = gradient
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            veil = view
            parent.addView(view, parent.indexOfChild(host).coerceAtLeast(0), FrameLayout.LayoutParams(-1, 1))
        }
        val current = view ?: return
        gradient.setDark(host.isDarkGlass())
        // Keep the screen-edge fade fixed while the bar slides; only its alpha
        // follows the existing animation. Never transform any feed/video view.
        val top = host.top.coerceAtLeast(0)
        val height = (parent.height - top).coerceAtLeast(1)
        val params = current.layoutParams as FrameLayout.LayoutParams
        if (params.width != parent.width || params.height != height || params.topMargin != top) {
            params.width = parent.width; params.height = height
            params.gravity = Gravity.TOP or Gravity.START; params.topMargin = top
            current.layoutParams = params
        }
        val actualBarTop = host.top + host.translationY + host.pivotY * (1f - host.scaleY)
        val clipTop = kotlin.math.ceil((actualBarTop - top).coerceAtLeast(0f)).toInt().coerceAtMost(height)
        if (visibleBounds.top != clipTop || visibleBounds.right != parent.width || visibleBounds.bottom != height) {
            visibleBounds.set(0, clipTop, parent.width, height)
            current.clipBounds = visibleBounds
        }
        val alpha = if (visible) host.alpha else 0f
        if (current.alpha != alpha) current.alpha = alpha
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        if (current.visibility != visibility) current.visibility = visibility
    }

    fun release() {
        val old = veil ?: return
        veil = null
        old.visibility = View.INVISIBLE
        // Detach can run during the parent's traversal; remove only this old
        // instance afterwards, leaving a newly rebound veil untouched.
        val parent = old.parent as? ViewGroup ?: return
        parent.post { if (old.parent === parent) parent.removeView(old) }
    }
}
