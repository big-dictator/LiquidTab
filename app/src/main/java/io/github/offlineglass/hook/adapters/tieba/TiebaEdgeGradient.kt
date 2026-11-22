package io.github.offlineglass.hook.adapters.tieba

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.home_glass.HomeEdgeColorGradient

/** Page tint only. AdapterOwnedOverlay keeps it out of both panels' optical input. */
internal class TiebaEdgeGradient(top: Boolean) {
    private class Layer(context: Context) : View(context), AdapterOwnedOverlay {
        private val actionBounds = RectF()
        private val actionHole = Path()

        fun avoidAction(bounds: RectF) {
            if (actionBounds == bounds) return
            actionBounds.set(bounds)
            actionHole.reset()
            if (!bounds.isEmpty) actionHole.addOval(bounds, Path.Direction.CW)
            invalidate()
        }

        override fun draw(canvas: Canvas) {
            if (actionHole.isEmpty) {
                super.draw(canvas)
                return
            }
            val save = canvas.save()
            canvas.clipOutPath(actionHole)
            super.draw(canvas)
            canvas.restoreToCount(save)
        }
    }
    private val gradient = HomeEdgeColorGradient(top, topAlpha = 160)
    private var layer: Layer? = null
    private val action = RectF()

    fun updateBottom(host: GlassHostLayout, enabled: Boolean) {
        if (!enabled) { clear(); return }
        val parent = host.parent as? FrameLayout ?: return
        if (host.width <= 0 || host.height <= 0) return
        val y = host.top.coerceIn(0, parent.height)
        update(host, y, parent.height - y, host.isShown)
        if (host.adapterDetachedActionBounds(action)) {
            val left = host.x + host.pivotX * (1f - host.scaleX)
            val top = host.y - y + host.pivotY * (1f - host.scaleY)
            action.set(left + action.left * host.scaleX, top + action.top * host.scaleY,
                left + action.right * host.scaleX, top + action.bottom * host.scaleY)
        } else {
            action.setEmpty()
        }
        layer?.avoidAction(action)
    }

    fun updateTop(host: GlassHostLayout, height: Int, visible: Boolean) {
        update(host, 0, height, visible)
    }

    private fun update(host: GlassHostLayout, top: Int, height: Int, visible: Boolean) {
        val parent = host.parent as? FrameLayout ?: return
        if (height <= 0) { layer?.visibility = View.GONE; return }
        if (layer?.parent !== parent) {
            clear()
            layer = Layer(host.context).apply {
                background = gradient
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                parent.addView(this, parent.indexOfChild(host).coerceAtLeast(0),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = top })
            }
        }
        val view = layer ?: return
        val visibility = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != visibility) view.visibility = visibility
        if (!visible) return
        gradient.setDark(host.isDarkGlass())
        val params = view.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin != top || params.height != height) {
            params.topMargin = top
            params.height = height
            view.layoutParams = params
        }
    }

    fun clear() {
        val view = layer ?: return
        layer = null
        val parent = view.parent as? ViewGroup ?: return
        view.visibility = View.GONE
        parent.post { if (view.parent === parent) parent.removeView(view) }
    }
}
