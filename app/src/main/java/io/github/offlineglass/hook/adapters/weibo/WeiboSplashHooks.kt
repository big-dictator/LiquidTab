package io.github.offlineglass.hook.adapters.weibo

import android.content.Context
import android.content.Intent
import android.app.Activity
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier

/** Skip ad acquisition through the same native continuation used for no-ad startup. */
internal object WeiboSplashHooks {
    private var installed = false
    private var warmInstalled = false
    fun install(context: Context, loader: ClassLoader) {
        installSdkNoFill(context, loader)
        if (installed) return
        runCatching {
            val app = context.applicationContext ?: context
            val binding = WeiboSplashIndex.resolveCached(app.applicationInfo.sourceDir,
                java.io.File(app.cacheDir, "liquidtab-weibo-splash-binding.properties").path)
            val owner = Class.forName(binding.owner(), false, loader)
            val load = owner.getDeclaredMethod(binding.load(), Activity::class.java).apply { isAccessible = true }
            val next = owner.getDeclaredMethod(binding.next()).apply { isAccessible = true }
            val show = owner.getDeclaredMethod(binding.show(), Intent::class.java).apply { isAccessible = true }
            check(listOf(load, next, show).all { it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) })
            // Stable SDK type, independently verified against the actor's unique ad field.
            val flash = Class.forName("com.sina.weibo.mobileads.view.FlashAd", false, loader)
            val field = owner.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == flash }.apply { isAccessible = true }
            check(flash.getMethod("closeAdDialog").returnType == Void.TYPE)
            XposedBridge.hookMethod(load, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!io.github.offlineglass.hook.HookConfigReader.read(app, app.packageName).enabled) return
                    // Native continuation owns login/visitor setup, deep links and finishing.
                    param.result = null
                    field.set(param.thisObject, null)
                    next.invoke(param.thisObject)
                }
            })
            XposedBridge.hookMethod(show, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!io.github.offlineglass.hook.HookConfigReader.read(app, app.packageName).enabled) return
                    // Original show handles a null FlashAd by starting its prepared Intent.
                    field.set(param.thisObject, null)
                }
            })
            installed = true
            android.util.Log.i("LiquidTab-Weibo", "Verified splash no-ad continuation installed")
        }.onFailure { XposedBridge.log("LiquidTab-Weibo splash contract unavailable: $it") }
    }

    private fun installSdkNoFill(context: Context, loader: ClassLoader) {
        if (warmInstalled) return
        runCatching {
            val app = context.applicationContext ?: context
            val flash = Class.forName("com.sina.weibo.mobileads.view.FlashAd", false, loader)
            val listenerType = Class.forName("com.sina.weibo.mobileads.controller.AdListener", false, loader)
            val adType = Class.forName("com.sina.weibo.mobileads.view.IAd", false, loader)
            val error = Class.forName("com.sina.weibo.mobileads.model.AdRequest\$ErrorCode", false, loader)
            val noFill = error.getField("NO_FILL").get(null)
            val getListener = flash.getMethod("getListener")
            check(getListener.returnType == listenerType && adType.isAssignableFrom(flash))
            val failure = listenerType.getMethod("onFailedToReceiveAd", adType, error)
            check(failure.returnType == Void.TYPE)
            // SDK's loadAndShowAd returns before creating its Dialog when this returns false.
            val load = flash.declaredMethods.single {
                it.name == "loadAdWithLoadManager" && it.parameterCount == 1 && it.returnType == Boolean::class.javaPrimitiveType
            }
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            XposedBridge.hookMethod(load, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!io.github.offlineglass.hook.HookConfigReader.read(app, app.packageName).enabled) return
                    param.result = false
                    val receiver = param.thisObject
                    val listener = getListener.invoke(receiver) ?: return
                    // Preserve native failure/resume notifications instead of showing a skip overlay.
                    main.post {
                        runCatching { failure.invoke(listener, receiver, noFill) }
                            .onFailure { XposedBridge.log("LiquidTab-Weibo SDK no-fill continuation: $it") }
                    }
                }
            })
            warmInstalled = true
        }.onFailure { XposedBridge.log("LiquidTab-Weibo warm splash SDK contract unavailable: $it") }
    }
}
