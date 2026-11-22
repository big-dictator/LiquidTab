package io.github.offlineglass.hook.adapters.quora

import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.home_glass.HomeEdgeColorGradient

/** Screen-anchored color fades only. No capture, blur, touch or extra frame pump. */
internal class QuoraEdgeGradients {
    private class Layer(context: android.content.Context) : View(context), AdapterOwnedOverlay
    private val topTint = HomeEdgeColorGradient(top = true, topAlpha = 160)
    private val bottomTint = HomeEdgeColorGradient(top = false)
    private var topLayer: Layer? = null
    private var bottomLayer: Layer? = null

    fun update(bottom: GlassHostLayout, top: GlassHostLayout?) {
        val parent = bottom.parent as? FrameLayout ?: return
        if (parent.width <= 0 || parent.height <= 0) return
        if (bottomLayer?.parent !== parent) {
            clear()
            fun add(tint: HomeEdgeColorGradient) = Layer(bottom.context).apply {
                background = tint
                isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // Both are below all module chrome and remain below native modals.
                parent.addView(this, parent.indexOfChild(bottom).coerceAtLeast(0),
                    FrameLayout.LayoutParams(1, 1))
            }
            topLayer = add(topTint); bottomLayer = add(bottomTint)
        }
        val dark = bottom.isDarkGlass()
        topTint.setDark(dark); bottomTint.setDark(dark)
        val inset = parent.rootWindowInsets?.getInsets(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
        // Match XhsTopBar: status inset + 6dp, peak alpha 160 and smoothstep fade.
        val topHeight = (inset + 6 * bottom.resources.displayMetrics.density).toInt()
            .coerceIn(1, parent.height)
        place(topLayer, parent.width, 0, topHeight, top?.takeIf { it.parent === parent })
        // Layout top excludes the animated translation: no per-frame resizing.
        val bottomTop = bottom.top.coerceIn(0, parent.height)
        place(bottomLayer, parent.width, bottomTop,
            (parent.height - bottomTop).coerceAtLeast(1), bottom)
    }

    private fun place(layer: Layer?, width: Int, top: Int, height: Int, host: View?) {
        layer ?: return
        val lp = layer.layoutParams as FrameLayout.LayoutParams
        if (lp.width != width || lp.height != height || lp.topMargin != top) {
            lp.width = width; lp.height = height; lp.topMargin = top
            layer.layoutParams = lp
        }
        val alpha = if (host?.isShown == true) host.alpha else 0f
        if (layer.alpha != alpha) layer.alpha = alpha
        val visibility = if (alpha > 0f) View.VISIBLE else View.INVISIBLE
        if (layer.visibility != visibility) layer.visibility = visibility
    }

    fun clear() {
        topLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bottomLayer?.let { (it.parent as? ViewGroup)?.removeView(it) }
        topLayer = null; bottomLayer = null
    }
}
