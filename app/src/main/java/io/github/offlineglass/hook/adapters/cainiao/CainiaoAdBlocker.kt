package io.github.offlineglass.hook.adapters.cainiao

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

internal object CainiaoAdBlocker {
    private const val TAG = "LiquidTabCainiaoAds"
    private const val SPLASH_SKIP_KEY = "is_hit_user_id_in_splash_white_list"
    private const val HOME = "com.cainiao.wireless.homepage"
    private val booleanType = Boolean::class.javaPrimitiveType!!
    private val intType = Int::class.javaPrimitiveType!!
    private val mainHandler = Handler(Looper.getMainLooper())

    fun install(context: Context, loader: ClassLoader) {
        if (context.packageName != "com.cainiao.wireless") return
        installCapability("cold/warm splash policy") { installSplashPolicy(loader) }
        installCapability("cached splash preview") { installSplashPreviewGuard(loader) }
        installCapability("splash creative activity") { installSplashActivityGuard(loader) }
        installCapability("homepage advertising poplayer") { installHomePoplayerGuard(loader) }
        installCapability("Meishu display ads") { installMeishuGuard(loader) }
        installCapability("CSJ full-screen video") { installCsjGuard(loader) }
    }

    private inline fun installCapability(name: String, install: () -> Unit) {
        runCatching { install() }
            .onSuccess { Log.i(TAG, "Installed $name guard") }
            .onFailure { Log.w(TAG, "Unavailable contract: $name", it) }
    }

    private fun installSplashPolicy(loader: ClassLoader) {
        val prefs = loader.loadClass("com.cainiao.wireless.utils.SharedPreUtils")
        require(prefs.getMethod("getInstance").returnType == prefs)
        val read = prefs.getMethod("getBooleanStorage", String::class.java, booleanType)
        require(read.returnType == booleanType)
        before(read) { param ->
            // Both launcher paths use this preference as the native no-request decision.
            if (prefs.isInstance(param.thisObject) && param.args[0] == SPLASH_SKIP_KEY) {
                param.result = true
            }
        }
    }

    private fun installSplashPreviewGuard(loader: ClassLoader) {
        val manager = loader.loadClass("$HOME.splash.preview.SplashAdsPreviewManager")
        val dto = loader.loadClass("$HOME.splash.preview.SplashAdsPreviewDTO")
        require(manager.declaredMethods.count {
            it.matches(booleanType, Activity::class.java, dto, booleanType)
        } == 1)
        val tryShow = manager.declaredMethods.single {
            it.matches(booleanType, Activity::class.java, booleanType)
        }
        before(tryShow) { it.result = false }
    }

    private fun installSplashActivityGuard(loader: ClassLoader) {
        val activity = loader.loadClass("$HOME.view.activity.AdsActivity").asSubclass(Activity::class.java)
        val api = loader.loadClass("$HOME.view.IAdsActivityView")
        require(api.isInterface && api.isAssignableFrom(activity))
        require(api.getMethod("goHomePage").returnType == Void.TYPE)
        val creativeKey = activity.getField("ADS_DTO").get(null) as String
        require(creativeKey == "ADS_DTO")
        before(activity.getDeclaredMethod("onCreate", Bundle::class.java)) { param ->
            // A restored or directly routed ad activity takes its native empty-creative path.
            (param.thisObject as Activity).intent?.removeExtra(creativeKey)
        }
    }

    private fun installHomePoplayerGuard(loader: ClassLoader) {
        val hybrid = "com.cainiao.wireless.components.hybrid.hybridinterface"
        val api = loader.loadClass("$hybrid.IPopupWindowInterface")
        val callback = loader.loadClass("$hybrid.PoplayerLoadResultListener")
        val activity = loader.loadClass("$HOME.view.activity.HomePageActivity").asSubclass(Activity::class.java)
        require(api.isInterface && api.isAssignableFrom(activity) && callback.isInterface)
        val show = api.getMethod("showPoplayerInfoDialog", String::class.java, callback)
        val failure = callback.getMethod("error", String::class.java)
        require(show.returnType == Void.TYPE && failure.returnType == Void.TYPE)
        val packageList = loader.loadClass("com.cainiao.wireless.packagelist.presentation.IHomePackageListPresent")
        val header = loader.loadClass("com.cainiao.wireless.packagelist.presentation.IHomepageHeaderPresenter")
        val bindings = WeakHashMap<Class<*>, PoplayerCaller?>()
        before(activity.getDeclaredMethod(show.name, *show.parameterTypes)) { param ->
            val listener = param.args[1] ?: return@before
            if (!callback.isInstance(listener) || (param.args[0] as? String).isNullOrBlank()) return@before
            val type = listener.javaClass
            val binding = synchronized(bindings) {
                if (!bindings.containsKey(type)) {
                    bindings[type] = runCatching { bindPoplayerCaller(type, packageList, header) }
                        .onFailure { Log.w(TAG, "Could not inspect poplayer caller", it) }.getOrNull()
                }
                bindings[type]
            } ?: return@before
            val presenter = binding.presenter.get(listener) ?: return@before
            if (binding.context.get(presenter) !== param.thisObject) return@before
            param.result = null
            failure.invoke(listener, "Home advertising poplayer disabled")
        }
    }

    private class PoplayerCaller(val presenter: Field, val context: Field)

    private fun bindPoplayerCaller(type: Class<*>, packageList: Class<*>, header: Class<*>): PoplayerCaller? {
        // The pit-204 caller is a closure of the dual-role homepage presenter.
        // The package-list JS bridge uses its own callback with no such presenter.
        val enclosing = type.enclosingMethod ?: return null
        val owner = enclosing.declaringClass
        if (!owner.name.startsWith("$HOME.presenter.") ||
            !packageList.isAssignableFrom(owner) || !header.isAssignableFrom(owner) ||
            !Modifier.isPrivate(enclosing.modifiers) || !enclosing.matches(Void.TYPE, String::class.java)
        ) return null
        val presenter = type.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == owner
        } ?: return null
        val context = owner.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Context::class.java
        } ?: return null
        presenter.isAccessible = true
        context.isAccessible = true
        return PoplayerCaller(presenter, context)
    }

    private fun installMeishuGuard(loader: ClassLoader) {
        val prefix = "com.meishu.sdk.core"
        val types = setOf(
            loader.loadClass("$prefix.ad.interstitial.InterstitialAdLoader"),
            loader.loadClass("$prefix.ad.fullscreenvideo.FullScreenVideoAdLoader"),
        )
        val api = loader.loadClass("$prefix.loader.IAdLoader")
        val owner = types.map { type ->
            require(api.isAssignableFrom(type))
            val load = type.getMethod("loadAd", Map::class.java)
            require(load.returnType == Void.TYPE)
            load.declaringClass
        }.distinct().single()
        val load = owner.getDeclaredMethod("loadAd", Map::class.java)
        val noAd = owner.getDeclaredMethod("handleNoAd", String::class.java)
        require(noAd.returnType == Void.TYPE)
        before(load) { param ->
            val adLoader = param.thisObject ?: return@before
            if (adLoader.javaClass !in types) return@before
            param.result = null
            noAd.invoke(adLoader, "Full-screen display ad disabled")
        }
    }

    private fun installCsjGuard(loader: ClassLoader) {
        val sdk = "com.bytedance.sdk.openadsdk"
        val manager = loader.loadClass("$sdk.TTAdManager")
        val native = loader.loadClass("$sdk.TTAdNative")
        val slot = loader.loadClass("$sdk.AdSlot")
        val getManager = loader.loadClass("$sdk.TTAdSdk").getMethod("getAdManager")
        val factory = manager.getMethod("createAdNative", Context::class.java)
        val load = native.methods.single {
            it.name == "loadFullScreenVideoAd" && it.returnType == Void.TYPE &&
                it.parameterTypes.size == 2 && it.parameterTypes[0] == slot
        }
        val listener = load.parameterTypes[1]
        val fail = listener.getMethod("onError", intType, String::class.java)
        require(Modifier.isStatic(getManager.modifiers) && getManager.returnType == manager)
        require(manager.isInterface && native.isInterface && factory.returnType == native)
        require(listener.isInterface && fail.returnType == Void.TYPE)

        val installed = mutableSetOf<Method>()
        fun bind(instance: Any, contract: Method, hook: XC_MethodHook) {
            val actual = instance.javaClass.getMethod(contract.name, *contract.parameterTypes)
            require(actual.returnType == contract.returnType && !Modifier.isAbstract(actual.modifiers))
            synchronized(installed) {
                if (installed.add(actual)) {
                    try {
                        XposedBridge.hookMethod(actual, hook)
                    } catch (failure: Throwable) {
                        installed.remove(actual)
                        throw failure
                    }
                }
            }
        }
        val loadHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val callback = param.args[1]
                param.result = null
                if (listener.isInstance(callback)) mainHandler.post {
                    runCatching { fail.invoke(callback, -1, "Full-screen display ad disabled") }
                        .onFailure { Log.w(TAG, "CSJ no-ad callback failed", it) }
                }
            }
        }
        val factoryHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val instance = param.result ?: return
                if (native.isInstance(instance)) bind(instance, load, loadHook)
            }
        }
        XposedBridge.hookMethod(getManager, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val instance = param.result ?: return
                if (manager.isInstance(instance)) bind(instance, factory, factoryHook)
            }
        })
    }

    private fun Method.matches(result: Class<*>, vararg args: Class<*>): Boolean =
        !Modifier.isStatic(modifiers) && !isSynthetic &&
            returnType == result && parameterTypes.contentEquals(args)

    private fun before(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = block(param)
        })
    }
}
