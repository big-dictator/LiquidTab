package io.github.offlineglass.hook.adapters.tieba

import android.app.Activity
import android.content.res.Configuration
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier

internal object TiebaSplashAds {
    fun install(loader: ClassLoader) {
        runCatching {
            val view = Class.forName("com.baidu.tieba.splash.SplashAdView", false, loader)
            check(FrameLayout::class.java.isAssignableFrom(view))
            val integer = Int::class.javaPrimitiveType!!
            val activity = view.declaredConstructors.single {
                val types = it.parameterTypes
                types.size == 2 && Activity::class.java.isAssignableFrom(types[0]) && types[1] == integer
            }.parameterTypes[0]
            val signature = arrayOf(activity, ViewGroup::class.java, integer)
            val delegatePackage = view.name.substringBeforeLast('.').substringBeforeLast('.')
            val initializer = view.declaredFields.asSequence()
                .filter {
                    !Modifier.isStatic(it.modifiers) &&
                        it.type.name.substringBeforeLast('.') == delegatePackage
                }
                .map { it.type }
                .filter { owner ->
                    owner.declaredMethods.any {
                        it.returnType == Void.TYPE &&
                            it.parameterTypes.contentEquals(signature + Long::class.javaPrimitiveType!!)
                    } && owner.declaredMethods.any {
                        it.returnType == Void.TYPE &&
                            it.parameterTypes.contentEquals(arrayOf(Configuration::class.java))
                    }
                }
                .flatMap { it.declaredMethods.asSequence() }
                .single {
                    !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(signature)
                }
            val switches = Class.forName("com.baidu.adp.lib.featureSwitch.SwitchManager", false, loader)
            val findType = switches.getDeclaredMethod("findType", String::class.java)
            check(findType.returnType == integer && !Modifier.isStatic(findType.modifiers))
            val keyOwner = Class.forName("com.baidu.tbadk.switchs.AdSdkSwitch", false, loader)
            val switchKey = keyOwner.getField("KEY_AD_SDK_SWITCH").get(null) as String
            check(switchKey.isNotBlank())

            val loading = ThreadLocal<Int>()
            XposedBridge.hookMethod(findType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (loading.get() != null && param.args[0] == switchKey) param.result = 0
                }
            })
            XposedBridge.hookMethod(initializer, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (view.isInstance(param.args[1])) loading.set((loading.get() ?: 0) + 1)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!view.isInstance(param.args[1])) return
                    val depth = loading.get() ?: return
                    if (depth == 1) loading.remove() else loading.set(depth - 1)
                }
            })
        }.onFailure { XposedBridge.log("[LiquidTab/Tieba] Splash contract unavailable: $it") }
    }
}
