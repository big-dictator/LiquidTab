package io.github.offlineglass.hook.adapters.huawei_health

import android.app.Activity
import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassInstaller
import java.util.WeakHashMap

/**
 * Removes the navigation-bar bottom fitting only on module-managed Health
 * windows; status-bar, side and IME insets are preserved untouched.
 *
 * Huawei Health fits its window above the gesture bar, which lifts the system
 * pill ("小白条") off the screen bottom on every adapted page. This mirrors the
 * proven XJTU recipe: the shared immersion path registers the decor view, the
 * dispatchApplyWindowInsets hook then reports zeroed navigationBars bottom
 * insets for registered windows only, and every Health activity re-asserts the
 * immersion on resume so no page can re-raise the pill.
 */
internal object HuaweiHealthInsets {
    private val windows = WeakHashMap<View, Boolean>()
    private var installed = false

    fun configure(decor: View, enabled: Boolean) {
        if (enabled) windows[decor] = true else windows.remove(decor)
        install()
    }

    fun install() {
        if (installed) return
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchApplyWindowInsets", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (windows[param.thisObject] != true) return
                val original = param.args.firstOrNull() as? WindowInsets ?: return
                val type = WindowInsets.Type.navigationBars()
                val visible = original.getInsets(type)
                val stable = original.getInsetsIgnoringVisibility(type)
                if (visible.bottom == 0 && stable.bottom == 0) return
                param.args[0] = WindowInsets.Builder(original)
                    .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
                    .setInsetsIgnoringVisibility(type, Insets.of(stable.left, stable.top, stable.right, 0))
                    .build()
            }
        })
        // Keep the navigation edge flush on every Health screen, including
        // activities that do not host the glass navigation themselves.
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName == PACKAGE_NAME) {
                    GlassInstaller.updateAdapterNavigationBarForPage(activity)
                }
            }
        })
        installed = true
    }

    private const val PACKAGE_NAME = "com.huawei.health"
}
