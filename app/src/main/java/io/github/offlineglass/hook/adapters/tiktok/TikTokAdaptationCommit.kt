package io.github.offlineglass.hook.adapters.tiktok

import android.view.View
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Verified commit contract: adaptation result + reason, on the native cell component.
 * Normalize before the native applier compares and writes dimensions/translations.
 */
internal object TikTokAdaptationCommit {
    fun install(loader: ClassLoader) {
        val component = Class.forName("com.ss.android.ugc.feed.platform.cell.component.adaption.CellAdaptionComponentV2", false, loader)
        val result = Class.forName("com.ss.android.ugc.aweme.videoadaption.adaptionparams.VideoAdaptionResult", false, loader)
        val commit = component.declaredMethods.single { it.returnType == Void.TYPE &&
            it.parameterTypes.contentEquals(arrayOf(result, String::class.java)) }
        val copy = result.declaredMethods.single { it.name == "copy" && it.parameterCount == 5 && it.returnType == result }
        val width = result.getDeclaredMethod("getWidth")
        val height = result.getDeclaredMethod("getHeight")
        val operator = result.getDeclaredMethod("getResultOperator")
        val roots = WeakHashMap<Any, java.lang.ref.WeakReference<View>>()
        XposedBridge.hookAllMethods(component, "onViewCreated", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = param.args.firstOrNull() as? View ?: return
                roots[param.thisObject] = java.lang.ref.WeakReference(root)
            }
        })
        XposedBridge.hookAllMethods(component, "onDestroyView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { roots.remove(param.thisObject) }
        })
        XposedBridge.hookAllMethods(component, commit.name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val root = roots[param.thisObject]?.get() ?: return
                val viewport = TikTokVideoCenter.feedViewport(root) ?: return
                val native = param.args.firstOrNull() ?: return
                val w = width.invoke(native) as Int
                val h = height.invoke(native) as Int
                if (w <= 0 || h <= 0) return
                val fit = TikTokPlayerLayout.fit(FrameLayout.LayoutParams(w, h), viewport.first, viewport.second,
                    TikTokPlayerLayout.tallVideoFill(root))
                param.args[0] = copy.invoke(native, fit.width, fit.height, 0f, 0f, operator.invoke(native))
            }
        })
    }
}
