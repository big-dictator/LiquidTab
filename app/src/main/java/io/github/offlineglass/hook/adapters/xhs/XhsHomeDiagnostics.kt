package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.io.PrintWriter

/** Explicit debug dump only; no frame loop, image capture, text or account data. */
internal object XhsHomeDiagnostics {
    fun install() {
        if (!io.github.offlineglass.BuildConfig.DEBUG) return
        XposedBridge.hookAllMethods(Activity::class.java, "dump", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != "com.xingin.xhs.index.v2.IndexActivityV2") return
                val args = param.args.getOrNull(3) as? Array<*> ?: return
                if (args.none { it == "liquidtab-xhs-chrome" }) return
                val out = param.args.getOrNull(2) as? PrintWriter ?: return
                val root = activity.window.decorView
                val location = IntArray(2)
                var budget = 3000
                val overlayField = runCatching { View::class.java.getDeclaredField("mOverlay").apply { isAccessible = true } }.getOrNull()
                val overlayGroup = runCatching { android.view.ViewOverlay::class.java.getDeclaredField("mOverlayViewGroup").apply { isAccessible = true } }.getOrNull()
                val renderNodeField = runCatching { View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true } }.getOrNull()
                fun visit(view: View, depth: Int) {
                    if (--budget < 0 || depth > 48 || !view.isShown) return
                    view.getLocationInWindow(location)
                    if (location[0] >= root.width || location[0] + view.width <= 0) return
                    val color = (view.background as? ColorDrawable)?.color?.toUInt()?.toString(16)
                    val group = view as? ViewGroup
                    out.println("XhsChrome ${view.javaClass.name} #${view.id.toUInt().toString(16)} " +
                        "xy=${location[0]},${location[1]} size=${view.width}x${view.height} alpha=${view.alpha} nodeAlpha=${(renderNodeField?.get(view) as? android.graphics.RenderNode)?.alpha} " +
                        "padding=${view.paddingLeft},${view.paddingTop},${view.paddingRight},${view.paddingBottom} " +
                        "clip=${view.clipBounds} children=${group?.clipChildren} paddingClip=${group?.clipToPadding} " +
                        "bg=${view.background?.javaClass?.name}:$color fg=${view.foreground?.javaClass?.name}")
                    runCatching {
                        val overlay = overlayField?.get(view)
                        val overlayView = overlay?.let { overlayGroup?.get(it) as? ViewGroup }
                        if (overlayView != null && overlayView.childCount > 0) {
                            out.println("XhsChrome overlay owner=${view.javaClass.name} count=${overlayView.childCount}")
                            for (i in 0 until overlayView.childCount) visit(overlayView.getChildAt(i), depth + 1)
                        }
                    }
                    if (XhsTopBinding.isRecycler(view)) runCatching {
                        val count = view.javaClass.getMethod("getItemDecorationCount").invoke(view) as Int
                        val getter = view.javaClass.getMethod("getItemDecorationAt", Int::class.javaPrimitiveType)
                        for (i in 0 until count) out.println("XhsChrome decoration owner=${view.javaClass.name} type=${getter.invoke(view, i)?.javaClass?.name}")
                    }
                    if (view is AdapterOwnedOverlay || view is GlassHostLayout || XhsTopBinding.isRecycler(view)) return
                    if (group != null) for (i in 0 until group.childCount) visit(group.getChildAt(i), depth + 1)
                }
                visit(root, 0)
                out.flush()
            }
        })
    }
}
