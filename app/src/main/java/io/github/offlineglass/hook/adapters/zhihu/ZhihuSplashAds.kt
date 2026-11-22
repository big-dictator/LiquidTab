package io.github.offlineglass.hook.adapters.zhihu

import android.content.Context
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/** Default policy: native no-ad decision, preserving launcher completion callbacks. */
internal object ZhihuSplashAds {
    private val installed = AtomicBoolean()
    fun install(context: Context, loader: ClassLoader) {
            if (!installed.compareAndSet(false, true)) return
            val info = context.applicationInfo
            val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().toList()
            runCatching {
                val api = loader.loadClass("com.zhihu.android.ad.LaunchAdInterface")
                check(api.isInterface)
                check(api.getMethod("isShowLaunchAd").returnType == Boolean::class.javaPrimitiveType)
                check(api.getMethod("showLaunchAd", String::class.java).returnType == Void.TYPE)
                check(api.getMethod("setNoLaunchAd").returnType == Void.TYPE)
                val names = ZhihuApkContract.implementors(paths, "Lcom/zhihu/android/ad/LaunchAdInterface;")
                val types = names.map { loader.loadClass(it) }.filter { api.isAssignableFrom(it) &&
                    !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
                check(types.size == 1) { "LaunchAdInterface implementor ambiguity: ${types.size}" }
                val type = types.single()
                val noAd = type.getMethod("setNoLaunchAd")
                val hooks = listOf(type.getMethod("isShowLaunchAd"),
                    type.getMethod("showLaunchAd", String::class.java), type.getMethod("onStart", Context::class.java))
                check(hooks.all { it.declaringClass != api } && noAd.declaringClass != api)
                for (method in hooks) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (method.name == "onStart") noAd.invoke(param.thisObject)
                            else param.result = if (method.name == "isShowLaunchAd") false else null
                        }
                    })
                }
                android.util.Log.i("LiquidTabZhihu", "Native cold/warm launch no-ad policy installed")
            }.onFailure {
                android.util.Log.e("LiquidTabZhihu", "Splash contract unavailable; native startup preserved", it)
            }
            runCatching { installRequestGuard(paths, loader) }.onFailure {
                android.util.Log.w("LiquidTabZhihu", "Native splash completion contract unavailable", it)
            }
    }

    private fun installRequestGuard(paths: List<String>, loader: ClassLoader) {
        val api = loader.loadClass("com.zhihu.android.launch.api.call.LaunchApi")
        val request = api.methods.single { it.name == "requestAd" && it.returnType == Void.TYPE &&
            it.parameterTypes.size == 3 && it.parameterTypes[0] == String::class.java &&
            it.parameterTypes[1].isInterface && it.parameterTypes[2] == View::class.java }
        val callback = request.parameterTypes[1]
        val fragment = loader.loadClass("com.zhihu.android.app.ui.fragment.ad.LaunchFragment")
        val owner = fragment.declaredClasses.single { callback.isAssignableFrom(it) }
        val completion = owner.getDeclaredMethod(checkNotNull(ZhihuApkContract.completion(paths, owner)))
        check(callback.getMethod(completion.name).returnType == Void.TYPE && completion.returnType == Void.TYPE)
        completion.isAccessible = true
        val implementations = ZhihuApkContract.implementors(paths, "Lcom/zhihu/android/launch/api/call/LaunchApi;")
            .map { loader.loadClass(it) }.filter { api.isAssignableFrom(it) &&
                !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
        val method = implementations.single().getMethod(request.name, *request.parameterTypes)
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val listener = param.args.getOrNull(1) ?: return
                if (listener.javaClass !== owner) return
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { completion.invoke(listener) }.onFailure {
                        android.util.Log.e("LiquidTabZhihu", "Native splash completion failed", it)
                    }
                }
                param.result = null
                android.util.Log.i("LiquidTabZhihu", "Blocked native splash request")
            }
        })
    }
}
