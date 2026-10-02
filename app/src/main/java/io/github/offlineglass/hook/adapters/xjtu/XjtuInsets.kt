package io.github.offlineglass.hook.adapters.xjtu

import android.graphics.Insets
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.GlassInstaller
import java.util.WeakHashMap

/** Remove navigation bottom fitting only on module-managed campus windows; preserve IME/status. */
internal object XjtuInsets {
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
        // Keep the navigation edge flush on every XJTU screen, including
        // activities that do not host the five-tab glass navigation.
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

    private const val PACKAGE_NAME = "com.supwisdom.xjtu"
}
