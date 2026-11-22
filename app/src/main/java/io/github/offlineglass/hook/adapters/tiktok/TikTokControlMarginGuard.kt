package io.github.offlineglass.hook.adapters.tiktok

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Douyin's pre-measure ownership for controls and the released native feed reservation. */
internal object TikTokControlMarginGuard {
    private data class Geometry(val height: Int?, val margin: Int?, val weight: Float?, val top: Int?)
    private val owned = WeakHashMap<View, Geometry>()
    private var installed = false
    fun own(view: View, margin: Int) = ownGeometry(view, null, margin, null)
    fun ownGeometry(view: View, height: Int?, margin: Int?, weight: Float?, top: Int? = null) {
        if (!installed) {
            XposedBridge.hookAllMethods(View::class.java, "requestLayout", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = param.thisObject as? View ?: return
                    val expected = synchronized(owned) { owned[target] } ?: return
                    val lp = target.layoutParams ?: return
                    // Mutate before the native measurement; never request layout recursively.
                    expected.height?.let { if (lp.height != it) lp.height = it }
                    if (lp is ViewGroup.MarginLayoutParams) expected.margin?.let {
                        if (lp.bottomMargin != it) lp.bottomMargin = it
                    }
                    if (lp is ViewGroup.MarginLayoutParams) expected.top?.let {
                        if (lp.topMargin != it) lp.topMargin = it
                    }
                    if (lp is LinearLayout.LayoutParams) expected.weight?.let {
                        if (lp.weight != it) lp.weight = it
                    }
                }
            })
            installed = true
        }
        synchronized(owned) { owned[view] = Geometry(height, margin, weight, top) }
    }
    fun release(view: View) { synchronized(owned) { owned.remove(view) } }
}
