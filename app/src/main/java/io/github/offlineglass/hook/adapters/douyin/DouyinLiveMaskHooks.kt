package io.github.offlineglass.hook.adapters.douyin

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Only the standalone live-preview bottom gradient; no changes to interactive layers. */
internal object DouyinLiveMaskHooks {
    private var installed = false
    private val masks = WeakHashMap<View, Boolean>()
    private var reported = false

    private fun matches(view: View, background: Drawable?): Boolean {
        if (masks.containsKey(view)) return true
        if (!DouyinBottomMaskShape.matches(view, background, live = true)) return false
        val parent = view.parent as? ViewGroup ?: return false
        // The verified live layout pairs this plain mask with its long-press layer.
        var hasLongPressLayer = false
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).javaClass.simpleName == "ClickOptimizeLongPressLayout") {
                hasLongPressLayer = true
                break
            }
        }
        if (!hasLongPressLayer) return false
        var ancestor: View? = parent
        repeat(16) {
            val node = ancestor ?: return false
            if (node.javaClass.simpleName == "LivePreviewFrameLayout") {
                masks[view] = true
                if (!reported) {
                    reported = true
                    XposedBridge.log("LiquidTab Douyin live bottom gradient matched by structure")
                }
                return true
            }
            ancestor = node.parent as? View
        }
        return false
    }

    fun install() {
        if (installed) return
        installed = true
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val view = p.thisObject as? View ?: return
                if (view.background != null && matches(view, view.background)) view.background = null
            }
        })
        val guard = object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val background = p.args.firstOrNull() as? Drawable ?: return
                val view = p.thisObject as? View ?: return
                if (matches(view, background)) p.args[0] = null
            }
        }
        XposedBridge.hookAllMethods(View::class.java, "setBackground", guard)
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", guard)
    }
}
