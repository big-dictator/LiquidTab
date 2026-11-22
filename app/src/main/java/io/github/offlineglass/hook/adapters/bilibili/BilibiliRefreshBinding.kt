package io.github.offlineglass.hook.adapters.bilibili

import android.content.Context
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Field

internal object BilibiliRefreshBinding {
    @Volatile private var roles: Pair<Field, Field>? = null
    @Volatile private var frameRoles: Pair<Field, Field>? = null
    fun prepare(context: Context, loader: ClassLoader) {
        // Cold discovery is bounded and off the UI thread; no DEX work in scrolling.
        Thread({
            runCatching {
                val found = BilibiliRefreshContract.resolve(context.applicationInfo.sourceDir,
                    java.io.File(context.codeCacheDir, "liquidtab-bilibili-refresh-v1.properties").path)
                fun field(role: String, valueType: Class<*>, static: Boolean = false): Field {
                    val ref = found.getProperty(role).split('|')
                    return loader.loadClass(ref[0].removePrefix("L").removeSuffix(";").replace('/', '.'))
                        .getDeclaredField(ref[1]).apply {
                            check(type == valueType)
                            check(java.lang.reflect.Modifier.isStatic(modifiers) == static)
                            isAccessible = true
                        }
                }
                if (found.containsKey("scale") && found.containsKey("custom")) {
                    roles = field("scale", Boolean::class.javaPrimitiveType!!) to field("custom", Boolean::class.javaPrimitiveType!!)
                }
                if (found.containsKey("frameReserved") && found.containsKey("frameTime")) {
                    frameRoles = field("frameReserved", Int::class.javaPrimitiveType!!, true) to
                        field("frameTime", Long::class.javaPrimitiveType!!)
                }
                XposedBridge.log("LiquidTab Bilibili native layout roles verified; refresh=${roles != null}, story=${frameRoles != null}")
                context.getExternalFilesDir(null)?.resolve("liquidtab-native-layout-binding.txt")?.writeText(
                    "refresh=${roles != null}; story=${frameRoles != null}\n$found\n")
            }.onFailure { XposedBridge.log("LiquidTab Bilibili refresh role binding unavailable: $it") }
        }, "LiquidTab-BiliRefreshBinding").start()
    }
    fun fields(view: android.view.View): Pair<Field, Field>? = roles?.takeIf {
        it.first.declaringClass.isInstance(view) && it.second.declaringClass.isInstance(view)
    }
    fun frameFields(view: android.view.View): Pair<Field, Field>? = frameRoles?.takeIf {
        it.first.declaringClass.isInstance(view) && it.second.declaringClass.isInstance(view)
    }
}
