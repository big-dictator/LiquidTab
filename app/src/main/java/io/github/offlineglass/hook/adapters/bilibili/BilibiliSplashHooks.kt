package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Use the app's no-splash path so startup, routing and home initialization still run. */
internal object BilibiliSplashHooks {
    fun install(loader: ClassLoader) {
        val entry = runCatching { XposedHelpers.findClass(
            "com.bilibili.lib.performance.EntryPointKt", loader,
        ) }.getOrNull()
        listOf("getBrandSplashDisable", "getSplashDisable").forEach { name ->
            runCatching {
                checkNotNull(entry) { "performance entry unavailable" }
                val method = entry.getDeclaredMethod(name)
                check(method.returnType == Boolean::class.javaPrimitiveType)
                check(entry.declaredMethods.count { it.name == name } == 1)
                XposedBridge.hookAllMethods(entry, name, object : XC_MethodHook() {
                    private var reported = false
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.isNotEmpty()) return
                        param.result = true
                        if (!reported) {
                            reported = true
                            android.util.Log.i("LiquidTabSplash", "tv.danmaku.bili: $name=true")
                        }
                    }
                })
            }.onFailure {
                XposedBridge.log("[LiquidTab/BilibiliSplash] $name unavailable: $it")
            }
        }
        // Cold and warm business splash paths share this manager. Returning no order/no
        // fragment preserves the native N4/c8 completion path and home initialization.
        runCatching {
            val manager = XposedHelpers.findClass("tv.danmaku.bili.splash.ad.core.SplashManager", loader)
            val signatures = listOf(
                "tv.danmaku.bili.splash.ad.model.SplashOrder" to listOf("boolean", "tv.danmaku.bili.splash.ad.model.SplashSource"),
                "tv.danmaku.bili.splash.ad.page.BaseSplash" to listOf("tv.danmaku.bili.splash.ad.model.SplashOrder"),
            )
            signatures.forEach { (result, arguments) ->
                val method = manager.declaredMethods.singleOrNull {
                    java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType.name == result &&
                        it.parameterTypes.map(Class<*>::getName) == arguments
                } ?: return@forEach
                // The local compile-only stub exposes only hookAllMethods; resolve the
                // standard runtime API to hook precisely this validated overload.
                XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                    .invoke(null, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
                    })
            }
        }.onFailure { XposedBridge.log("[LiquidTab/BilibiliSplash] business fallback unavailable: $it") }
    }
}
