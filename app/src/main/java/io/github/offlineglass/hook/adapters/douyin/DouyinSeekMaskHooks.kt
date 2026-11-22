package io.github.offlineglass.hook.adapters.douyin

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.SeekBar
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Only the inflated seek mask background, independent of labels and touch handling. */
internal object DouyinSeekMaskHooks {
    private var installed = false
    private val masks = WeakHashMap<View, Boolean>()
    private var reported = false

    private fun matches(view: View, background: Drawable?): Boolean {
        if (masks.containsKey(view)) return true
        if (!DouyinBottomMaskShape.matches(view, background, live = false)) return false
        val parent = view.parent as? ViewGroup ?: return false
        if (parent.childCount > 16) return false
        // 40.8 xz1.xml -> xz2.xml: the 280dp gradient is a lazily inflated
        // direct child of DProSeekBar, not necessarily attached after its siblings.
        if (parent.javaClass.name == "com.ss.android.ugc.aweme.o.seekbar.DProSeekBar") {
            masks[view] = true
            return true
        }
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is SeekBar && child.javaClass.name.startsWith("com.ss.android.ugc.aweme.o.seekbar.")) {
                masks[view] = true
                if (!reported) {
                    reported = true
                    XposedBridge.log("LiquidTab Douyin seek bottom gradient matched by structure")
                }
                return true
            }
        }
        return false
    }

    fun install() {
        if (installed) return
        installed = true
        XposedBridge.hookAllMethods(ViewStub::class.java, "inflate", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val view = p.result as? View ?: return
                if (matches(view, view.background)) view.background = null
            }
        })
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
