package io.github.offlineglass.hook.adapters.spotify

import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Remove the native bottom status strip, including its black background/space. */
internal object SpotifyIndicatorHooks {
    private var installed = false
    private val indicators = java.util.WeakHashMap<View, Boolean>()

    fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val mainLayout = loader.loadClass("com.spotify.mainlayout.ui.view.MainLayout")
            val getter = mainLayout.getMethod("getLimitedExperienceIndicatorContainer")
            // Native visibility management can restore this strip on resume or
            // new connection-state emissions. Enforce GONE at the write itself.
            XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (indicators.containsKey(param.thisObject)) param.args[0] = View.GONE
                }
            })
            val hooks = XposedBridge.hookAllMethods(mainLayout, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val indicator = getter.invoke(param.thisObject) as? View ?: return
                    indicators[indicator] = true
                    indicator.visibility = View.GONE
                    indicator.alpha = 0f
                }
            })
            check(hooks.isNotEmpty()) { "main layout inflation hook missing" }
            installed = true
            XposedBridge.log("[SpotifyGlass] native bottom status strip suppression installed")
        }.onFailure { XposedBridge.log("[SpotifyGlass] status strip suppression unavailable: $it") }
    }
}
