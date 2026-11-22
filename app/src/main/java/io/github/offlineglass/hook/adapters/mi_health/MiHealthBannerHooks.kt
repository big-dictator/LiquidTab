package io.github.offlineglass.hook.adapters.mi_health

import android.content.Context
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.HookConfigReader
import java.lang.reflect.Modifier

/** Verified semantic business requests only: no compiler-lambda names or minified callbacks. */
internal object MiHealthBannerHooks {
    fun install(loader: ClassLoader, packageName: String) {
        val application = runCatching {
            XposedHelpers.findClass("com.xiaomi.fitness.common.extensions.ApplicationExtKt", loader).getMethod("getApplication")
        }.getOrNull()
        fun enabled(): Boolean {
            val app = runCatching { application?.invoke(null) as? Context }.getOrNull() ?: return false
            return HookConfigReader.read(app, packageName).enabled
        }
        runCatching {
            val health = XposedHelpers.findClass("com.xiaomi.fitness.homepage.HealthV2Fragment", loader)
            for (semantic in listOf("requestOperationalBanners", "requestHealthTabBanner")) {
                val entry = health.declaredMethods.single { it.name == semantic && it.parameterTypes.isEmpty() &&
                    it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) }
                XposedBridge.hookMethod(entry, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) { if (enabled()) param.result = null }
                })
            }
            XposedBridge.hookAllMethods(health, "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = param.args.firstOrNull() as? View ?: return
                    if (!enabled()) return
                    val id = root.resources.getIdentifier("healthBannerRecyclerView", "id", packageName)
                    if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
                }
            }).also { check(it.isNotEmpty()) { "Health view lifecycle contract missing" } }
        }.onFailure { XposedBridge.log("[OfflineGlass][MiHealth] health banner contract unavailable: $it") }
        runCatching {
            val owner = XposedHelpers.findClass("com.xiaomi.fitness.device.manager.ui.tab.DeviceTabV4ViewModel", loader)
            val entry = owner.declaredMethods.single { method ->
                val p = method.parameterTypes
                method.name == "fillBannerList" && !Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                    p.size == 3 && List::class.java.isAssignableFrom(p[0]) &&
                    p[1].name == "com.fitness.banner.export.BannerRequestParamV2" && p[2] == Boolean::class.javaPrimitiveType
            }
            XposedBridge.hookMethod(entry, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    // The verified method does only banner fetching. Its success path creates the type-3 ad item.
                    runCatching { (param.args[0] as? MutableList<*>)?.clear() }
                    param.result = null
                }
            })
        }.onFailure { XposedBridge.log("[OfflineGlass][MiHealth] device banner request contract unavailable: $it") }
    }
}
