package io.github.offlineglass.hook.adapters.douyin

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Actual 40.8 sidebar fragment roots, verified from classes40.dex.
 * Cached/preloaded fragments are not open drawers: test the clipped visible
 * intersection on each frame, including partial interactive swipe exposure.
 * No label, obfuscated resource ID, global Fragment hook or tree scan.
 */
internal object DouyinSidebarPages {
    private val roots = WeakHashMap<View, Boolean>()
    private val bounds = Rect()
    private var installed = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        for (name in arrayOf(
            "com.ss.android.ugc.aweme.sidebar.panel.home.HomeSideBarFragmentV2",
            "com.ss.android.ugc.aweme.sidebar.panel.combine.HomeSideBarFragmentV2New",
            "com.ss.android.ugc.aweme.sidebar.profilesidebar.panel.ProfileSideBarFragment",
        )) {
            runCatching {
                val type = Class.forName(name, false, loader)
                val create = type.getDeclaredMethod("onCreateView", LayoutInflater::class.java,
                    ViewGroup::class.java, Bundle::class.java)
                require(View::class.java.isAssignableFrom(create.returnType))
                XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java,
                    XC_MethodHook::class.java).invoke(null, create, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.result as? View ?: return
                        roots[root] = true
                        android.util.Log.i("OfflineGlassDouyin", "sidebar bound fragment=$name root=${root.javaClass.name}")
                        // One small binding record, not a frame-by-frame diagnostic walk.
                        runCatching {
                            java.io.File(root.context.getExternalFilesDir(null), "dy_sidebar_binding_status.txt")
                                .writeText("time=${System.currentTimeMillis()} fragment=$name root=${root.javaClass.name}\n")
                        }
                    }
                })
            }.onFailure {
                XposedBridge.log("[LiquidTab] sidebar binding unavailable $name: $it")
            }
        }
    }

    fun isVisible(window: View?): Boolean {
        window ?: return false
        for (root in roots.keys) {
            if (root.rootView !== window || !root.isAttachedToWindow || !root.isShown ||
                root.width <= 0 || root.height <= 0) continue
            var node: View? = root
            var alpha = 1f
            while (node != null) { alpha *= node.alpha; node = node.parent as? View }
            if (alpha <= .01f || !root.getGlobalVisibleRect(bounds)) continue
            // Any real opening begins the exit animation; wait until the
            // drawer has fully left before starting the reverse transition.
            if (bounds.width() > 1 && bounds.height() > window.height / 3) return true
        }
        return false
    }
}
