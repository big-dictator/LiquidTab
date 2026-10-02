package io.github.offlineglass.hook.adapters.zhihu

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Native drawer state and SettingsFragment root, evaluated every host frame. */
internal object ZhihuSettingsVisibility {
    private val settingsRoots = WeakHashMap<View, Boolean>()

    fun install(loader: ClassLoader) {
        val type = runCatching { XposedHelpers.findClass(
            "com.zhihu.android.app.ui.fragment.preference.SettingsFragment", loader)
        }.getOrNull() ?: return
        XposedBridge.hookAllMethods(type, "onViewCreated", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                (param.args.firstOrNull() as? View)?.let { settingsRoots[it] = true }
            }
        })
    }

    fun allowsNavigation(root: View?): Boolean {
        root ?: return true
        // MainActivity's i controller owns mine_sidebar_drawer_layout and
        // mine_sidebar_drawer_container. DrawerLayout visibility is independent
        // of the retained native navigation row's visibility.
        val id = root.resources.getIdentifier("mine_sidebar_drawer_layout", "id", "com.zhihu.android")
        val drawer = root.findViewById<View>(id)
        val contentId = root.resources.getIdentifier("mine_sidebar_drawer_container", "id", "com.zhihu.android")
        val content = root.findViewById<View>(contentId)
        if (drawer != null && content != null && drawer.isShown &&
            runCatching { XposedHelpers.callMethod(drawer, "isDrawerVisible", content) as? Boolean }.getOrNull() == true
        ) return false
        return settingsRoots.keys.none { it.isAttachedToWindow && it.isShown && it.rootView === root.rootView }
    }
}
