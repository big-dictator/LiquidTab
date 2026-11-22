package io.github.offlineglass.hook.adapters.xhs

import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Properties

/** Native failure/handoff roles come from this APK's semantic call contracts. */
internal object XhsSplashAdsHooks {
    fun installResolved(roles: Properties, loader: ClassLoader, methods: Map<String, Method>) {
        fun hook(role: String, callback: XC_MethodHook) {
            val method = methods[role] ?: return
            runCatching { XposedBridge.hookMethod(method, callback) }
                .onFailure { android.util.Log.w("LiquidTabXhsBinding", "$role hook unavailable", it) }
        }
        val failure = methods["coldError"]
        if (failure != null) hook("coldGetter", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result?.takeIf { failure.declaringClass.isInstance(it) }?.let { failure.invoke(it) }
            }
        })
        val completion = methods["coldCompletion"]
        val absent = completion?.parameterTypes?.firstOrNull()?.takeIf { it.name == "com.google.common.base.Optional" }
            ?.getMethod("absent")?.invoke(null)
        if (absent != null) hook("coldCompletion", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = absent }
        })
        for (role in listOf("coldHandoff", "coldLinker")) hook(role, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = null }
        })
        val noAd = methods["hotFailure"] ?: return
        val constructor = runCatching {
            val parts = roles.getProperty("hotConstructor")?.split('|') ?: return@runCatching null
            XhsNativeBindings.type(parts[0], loader).getDeclaredConstructor(
                *parts[3].split(',').map { XhsNativeBindings.type(it, loader) }.toTypedArray()).apply { isAccessible = true }
        }.getOrNull() ?: return
        for (role in listOf("hotLoad", "hotShow")) hook(role, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() !is WeakReference<*>) return
                noAd.invoke(constructor.newInstance(param.thisObject, param.args[0]), 0)
                param.result = null
            }
        })
    }
}
