package io.github.offlineglass.hook.adapters.qqmusic

import android.app.Activity
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.HookConfigReader
import org.json.JSONObject
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Promo suppression for QQMusic's minibar tips and song-membership popup.
 *
 * The tip banners and the Kuikly dialog live behind heavily obfuscated factory /
 * accessor names that drift between builds. Per the robustness spec we bind them
 * through stable contracts instead: the tip factories are located by their semantic
 * return types (the tip view classes), the dialog entry by its parameter shape, and
 * the router payload by its JsonObject return type and the "dialog_song_popup"
 * constant anchor. No obfuscated short name is used to gate behaviour.
 */
internal object QqMusicPromoHooks {
    private const val TIP_PACKAGE = "com.tencent.qqmusic.minibarviptips"
    private val viewNames = setOf("$TIP_PACKAGE.view.MinibarTopTipView", "$TIP_PACKAGE.view.MinibarTopVipCommTipView")
    private const val KUIKLY_FRAGMENT = "com.tme.qqmusic.knative.kuikly.container.KuiklyRenderFragment"
    private const val ROUTER_INFO = "com.tme.qqmusic.knative.data.KuiklyRouterInfo"
    private const val SONG_POPUP_SCENE = "dialog_song_popup"

    // Resolved accessors, keyed by the live class identity so an APK update re-resolves.
    private val sceneAccessors = WeakHashMap<Class<*>, Method>()
    private val dataAccessors = WeakHashMap<Class<*>, Method>()

    fun suppress(view: View): Boolean {
        if (view.javaClass.name !in viewNames || !HookConfigReader.read(view.context, targetSpec.packageName).enabled) return false
        view.animate().cancel()
        if (view.visibility != View.GONE) view.visibility = View.GONE
        if (view.background != null) view.background = null
        if (view.foreground != null) view.foreground = null
        return true
    }

    fun install(loader: ClassLoader) {
        installTipSuppression(loader)
        installSongMembershipPopup(loader)
    }

    /** Suppress tips both at creation (factory) and at birth (constructor). */
    private fun installTipSuppression(loader: ClassLoader) {
        runCatching {
            val manager = XposedHelpers.findClass("$TIP_PACKAGE.MinibarTipManager", loader)
            // The lazy factories are located by their semantic return types (the tip
            // views). Discarding the result rejects the banner before it is shown.
            val factories = manager.declaredMethods.filter { it.returnType.name in viewNames }
            check(factories.isNotEmpty()) { "no top-tip factory resolved" }
            for (factory in factories) {
                XposedBridge.hookMethod(factory, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.result as? View ?: return
                        if (HookConfigReader.read(view.context, targetSpec.packageName).enabled) {
                            suppress(view)
                            param.setResult(null)
                        }
                    }
                })
            }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] promo factory hook failed: $it") }
        // Net for any other creation path: hide the banner as soon as it exists.
        for (name in viewNames) runCatching {
            val type = XposedHelpers.findClass(name, loader)
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (HookConfigReader.read(view.context, targetSpec.packageName).enabled) suppress(view)
                }
            })
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] promo constructor hook failed: $it") }
    }

    /** The Kuikly dialog is rejected by its parameter shape, before the window is built. */
    private fun installSongMembershipPopup(loader: ClassLoader) {
        runCatching {
            val fragment = XposedHelpers.findClass(KUIKLY_FRAGMENT, loader)
            val infoType = XposedHelpers.findClass(ROUTER_INFO, loader)
            // e(Activity, info) / f(Activity, url) converge on the private
            // (Activity, fragment, router-info) dialog builder; match it by shape.
            val builders = (listOf(fragment) + fragment.declaredClasses.toList()).flatMap { type ->
                type.declaredMethods.filter { method ->
                    method.parameterTypes.size == 3 &&
                        Activity::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                        method.parameterTypes[1] == fragment &&
                        method.parameterTypes[2] == infoType && method.returnType == fragment
                }
            }
            check(builders.size == 1) { "Kuikly dialog contract is missing or ambiguous: ${builders.size}" }
            for (method in builders) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.args.getOrNull(0) as? Activity ?: return
                        val info = param.args.getOrNull(2) ?: return
                        if (!HookConfigReader.read(activity, targetSpec.packageName).enabled) return
                        if (isSongMembershipPopup(info)) {
                            param.setResult(null)
                            XposedBridge.log("[OfflineGlass][QQMusic] suppressed song membership popup")
                        }
                    }
                })
            }
        }.onFailure { XposedBridge.log("[OfflineGlass][QQMusic] membership popup hook failed: $it") }
    }

    private fun isSongMembershipPopup(info: Any): Boolean {
        if (!sceneMatches(info)) return false
        val data = dataObject(info) ?: return false
        val params = runCatching { JSONObject(data.toString()) }.getOrNull() ?: return false
        return params.optString("__alert_id") == "21" || params.optString("aid").contains(".vip.")
    }

    /**
     * The scene accessor is resolved by the "dialog_song_popup" constant anchor and
     * cached only on a positive match, so unrelated popups never lock in a wrong handle.
     */
    private fun sceneMatches(info: Any): Boolean {
        val type = info.javaClass
        sceneAccessors[type]?.let {
            return runCatching { it.invoke(info) == SONG_POPUP_SCENE }.getOrDefault(false)
        }
        for (method in type.declaredMethods) {
            if (method.parameterTypes.isEmpty() && method.returnType == String::class.java) {
                val value = runCatching { method.apply { isAccessible = true }.invoke(info) }.getOrNull()
                if (value == SONG_POPUP_SCENE) {
                    sceneAccessors[type] = method
                    return true
                }
            }
        }
        return false
    }

    /** The payload accessor is identified by its JsonObject return type (semantic). */
    private fun dataObject(info: Any): Any? {
        val type = info.javaClass
        val method = dataAccessors[type] ?: type.declaredMethods.singleOrNull {
            it.parameterTypes.isEmpty() && it.returnType.name == "com.google.gson.JsonObject"
        }?.also { dataAccessors[type] = it } ?: return null
        return runCatching { method.apply { isAccessible = true }.invoke(info) }.getOrNull()
    }
}
