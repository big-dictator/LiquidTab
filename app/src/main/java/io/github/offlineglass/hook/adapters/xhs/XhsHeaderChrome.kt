package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/** Inspect only the verified native header's direct siblings, never feed cards.
 * Late ViewStub inflation and return/theme updates must not escape cached-null binding.
 */
internal class XhsHeaderChrome(private val tabs: ViewGroup) {
    private var reported = 0
    fun suppress(hide: (View, Boolean) -> Unit) {
        val owner = tabs.parent as? ViewGroup ?: return
        var pagers = 0
        var decoration: View? = null
        var separator: View? = null
        var decorations = 0
        var separators = 0
        val tabIndex = owner.indexOfChild(tabs)
        val density = tabs.resources.displayMetrics.density
        for (i in 0 until owner.childCount) {
            val view = owner.getChildAt(i)
            if (view.javaClass.name == "com.xingin.xhs.homepage.container.viewpager.HomeViewPager") pagers++
            if (view is ViewGroup && view !== tabs) for (child in 0 until view.childCount) {
                if (view.getChildAt(child).javaClass.name == "com.xingin.xhs.homepage.container.viewpager.HomeViewPager") pagers++
            }
            if (view.isClickable || view.hasOnClickListeners() || view.width < owner.width * .9f) continue
            // Native image is a noninteractive sibling behind HomeTabLayout,
            // limited to the header. The content pager remains untouched.
            if (view is ImageView && i < tabIndex && view.height > 0 && view.top <= tabs.top &&
                view.bottom >= tabs.bottom && view.height < owner.height / 2) {
                decoration = view; decorations++
            }
            if (view.javaClass == View::class.java && i > tabIndex &&
                view.height in 1..(3f * density).toInt().coerceAtLeast(1) &&
                kotlin.math.abs(view.top - tabs.bottom) <= 12f * density) {
                separator = view; separators++
            }
        }
        val failure = when {
            pagers != 1 || tabIndex < 0 -> 1
            decorations > 1 -> 2
            separators > 1 -> 3
            else -> 0
        }
        if (failure != reported) {
            reported = failure
            if (failure != 0) android.util.Log.w("LiquidTabXhsTop",
                "header chrome contract rejected: pagers=$pagers images=$decorations separators=$separators")
        }
        if (pagers != 1 || tabIndex < 0) return
        // Ambiguous siblings are rejected rather than guessing an ID or class.
        if (decorations == 1) decoration?.let { hide(it, true) }
        if (separators == 1) separator?.let { hide(it, true) }
    }
}
