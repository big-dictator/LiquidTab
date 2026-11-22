package io.github.offlineglass.hook.adapters.amap

import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage

/** Uses Amap's own skip control and its native skipAd path. */
internal object AmapSplashAd {
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val skipButton = runCatching {
            XposedHelpers.findClass("com.autonavi.minimap.component.SkipButton", lpparam.classLoader)
        }.getOrNull() ?: return
        // SkipButton's verified first-layout callback runs before its first draw.
        // Its constructor has already installed the native skipAd listener.
        XposedBridge.hookAllMethods(skipButton, "onGlobalLayout", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.isAttachedToWindow && view.hasOnClickListeners()) view.performClick()
            }
        })
        XposedBridge.hookAllConstructors(skipButton, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                // The constructor installs the native click listener. View.post runs
                // after attachment, when the splash manager can finish normally.
                view.post {
                    if (view.isAttachedToWindow && view.hasOnClickListeners()) {
                        view.performClick()
                    }
                }
            }
        })
    }
}
