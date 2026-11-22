package io.github.offlineglass.hook.adapters.bilibili_in

import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.reflect.Modifier

/** International builds obfuscate the flag reader; match its exact signature and keys. */
internal object BilibiliInSplashHooks {
    fun install(loader: ClassLoader) {
        runCatching {
            val keyClass = XposedHelpers.findClass("com.bilibili.lib.performance.PerformanceConfigKey", loader)
            val disabledKeys = listOf("BRAND_SPLASH_DISABLE", "SPLASH_DISABLE").map {
                keyClass.getDeclaredField(it).apply { isAccessible = true }.get(null)
            }
            val entry = XposedHelpers.findClass("com.bilibili.lib.performance.i", loader)
            val readers = entry.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes.contentEquals(arrayOf(keyClass))
            }
            check(readers.size == 1) { "ambiguous performance flag reader" }
            val reader = readers.single()
            check(entry.declaredMethods.count { it.name == reader.name } == 1)
            XposedBridge.hookAllMethods(entry, reader.name, object : XC_MethodHook() {
                private val reported = mutableSetOf<Int>()
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size != 1) return
                    val index = disabledKeys.indexOfFirst { it === param.args[0] }
                    if (index < 0) return
                    param.result = true
                    if (reported.add(index)) {
                        android.util.Log.i("LiquidTabSplash", "com.bilibili.app.in: ${disabledKeys[index]}=true")
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("[LiquidTab/BilibiliInSplash] unsupported performance flags: $it")
        }
    }
}
