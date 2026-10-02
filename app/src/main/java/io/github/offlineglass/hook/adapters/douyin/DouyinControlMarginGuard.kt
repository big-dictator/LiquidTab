package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

/** Keep an adapter-owned controls margin intact before native remeasurement. */
internal object DouyinControlMarginGuard {
    private val owned = WeakHashMap<View, Int>()
    private var installed = false
    fun own(view: View, margin: Int) {
        if (!installed) {
            XposedBridge.hookAllMethods(View::class.java, "requestLayout", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val view = p.thisObject as? View ?: return
                    val margin = synchronized(owned) { owned[view] } ?: return
                    val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    // No recursive requestLayout and no additional traversal.
                    if (params.bottomMargin != margin) {
                        params.bottomMargin = margin
                    }
                }
            })
            installed = true
        }
        synchronized(owned) { if (margin > 0) owned[view] = margin else owned.remove(view) }
    }
    fun release(view: View) {
        synchronized(owned) { owned.remove(view) }
    }
}
