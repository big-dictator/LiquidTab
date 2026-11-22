package io.github.offlineglass.hook.adapters.tiktok

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Resolve the native photo geometry by capabilities, not obfuscated field/class names. */
internal object TikTokPhotoLayout {
    fun install(loader: ClassLoader) {
        val holder = XposedHelpers.findClass(
            "com.ss.android.ugc.aweme.ui.feed.subphoto.holders.SmartImagePhotoViewHolderV2", loader)
        val content = holder.declaredFields.single { it.type == ViewGroup::class.java }.apply { isAccessible = true }
        val candidates = holder.declaredFields.map { it.type }.distinct().mapNotNull { type ->
            runCatching {
                val frame = type.declaredFields.single { it.type == ViewGroup::class.java }
                val setter = type.declaredMethods.single { it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType)) }
                frame.isAccessible = true
                frame to setter
            }.getOrNull()
        }
        val (frame, setter) = candidates.single()
        val adaptation = frame.declaringClass
        // Native LIZ stores the resting ratio and commits it through 0EL5.LJIIJJI.
        // Keep native pinch/clear-mode animations and their restore ratio consistent.
        XposedBridge.hookAllMethods(adaptation, setter.name, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size != 1 || param.args[0] !is Float) return
                val view = frame.get(param.thisObject) as? View ?: return
                if (TikTokVideoCenter.feedViewport(view) != null) param.args[0] = 1f
            }
        })
        XposedBridge.hookAllConstructors(holder, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = content.get(param.thisObject) as? View ?: return
                if (TikTokVideoCenter.feedViewport(view) == null) return
                val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                val percent = runCatching { lp.javaClass.getField("matchConstraintPercentHeight") }.getOrNull() ?: return
                // ConstraintLayout percent mode: height=0, percent=1. Existing FIT_CENTER
                // image hierarchy now centres against the physical fullscreen canvas.
                percent.setFloat(lp, 1f)
                lp.height = 0
                lp.topMargin = 0
                lp.bottomMargin = 0
                view.layoutParams = lp
            }
        })
    }
}
