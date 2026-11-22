package io.github.offlineglass.hook.adapters.meituan

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Original values survive rebinds without keeping obsolete view trees alive.
 * Only an explicit module-disabled config restores them. */
internal object MeituanTakeoutOwnership {
    private class Original {
        var paint = false; var background: Drawable? = null; var foreground: Drawable? = null
        var alpha: Float? = null; var visibility: Int? = null
        var height: Int? = null; var measured = 0; var bottomMargin: Int? = null
        var padding: IntArray? = null; var clip = true
    }
    private val originals = WeakHashMap<View, Original>()
    fun paint(view: View) {
        val value = originals.getOrPut(view) { Original() }
        if (!value.paint) { value.paint = true; value.background = view.background; value.foreground = view.foreground }
    }
    fun alpha(view: View, original: Float = view.alpha) { val value = originals.getOrPut(view) { Original() }; if (value.alpha == null) value.alpha = original }
    fun visibility(view: View) { val value = originals.getOrPut(view) { Original() }; if (value.visibility == null) value.visibility = view.visibility }
    fun layout(view: View): Int {
        val value = originals.getOrPut(view) { Original() }
        if (value.height == null) {
            value.height = view.layoutParams?.height ?: view.height; value.measured = view.measuredHeight
            value.bottomMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin
        }
        return value.measured
    }
    fun padding(view: ViewGroup): IntArray {
        val value = originals.getOrPut(view) { Original() }
        if (value.padding == null) {
            value.padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            value.clip = view.clipToPadding
        }
        return value.padding!!
    }
    fun restoreWhenDisabled() {
        originals.entries.toList().forEach { (view, original) ->
            if (original.paint) { view.background = original.background; view.foreground = original.foreground }
            original.alpha?.let { view.alpha = it }; original.visibility?.let { view.visibility = it }
            original.padding?.let { p ->
                view.setPadding(p[0], p[1], p[2], p[3]); (view as? ViewGroup)?.clipToPadding = original.clip
            }
            original.height?.let { height ->
                view.layoutParams?.let { params ->
                    params.height = height
                    (params as? ViewGroup.MarginLayoutParams)?.let { original.bottomMargin?.let { margin -> it.bottomMargin = margin } }
                    view.layoutParams = params
                }
                if (original.measured > 0 && view.isAttachedToWindow) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(original.measured, View.MeasureSpec.EXACTLY))
                    view.layout(view.left, view.top, view.right, view.top + original.measured)
                }
                view.requestLayout()
            }
        }
        originals.clear()
    }
}
