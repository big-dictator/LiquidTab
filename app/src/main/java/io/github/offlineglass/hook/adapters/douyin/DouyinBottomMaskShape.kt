package io.github.offlineglass.hook.adapters.douyin

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup

/** Match the verified mask shape, not version-dependent obfuscated resource IDs. */
internal object DouyinBottomMaskShape {
    fun matches(view: View, background: Drawable?, live: Boolean): Boolean {
        if (view.javaClass != View::class.java || view.isClickable || view.isLongClickable) return false
        val params = view.layoutParams ?: return false
        val heightDp = params.height / view.resources.displayMetrics.density
        if (params.width != ViewGroup.LayoutParams.MATCH_PARENT || heightDp !in 80f..320f) return false
        val gradient = background as? GradientDrawable ?: return false
        val colors = gradient.colors ?: return false
        if (colors.size != 2 || colors.any { Color.red(it) != 0 || Color.green(it) != 0 || Color.blue(it) != 0 }) return false
        return if (live) {
            gradient.orientation == GradientDrawable.Orientation.BOTTOM_TOP &&
                Color.alpha(colors[0]) == 128 && Color.alpha(colors[1]) == 0
        } else {
            gradient.orientation == GradientDrawable.Orientation.TOP_BOTTOM &&
                Color.alpha(colors[0]) == 0 && Color.alpha(colors[1]) == 168
        }
    }
}
