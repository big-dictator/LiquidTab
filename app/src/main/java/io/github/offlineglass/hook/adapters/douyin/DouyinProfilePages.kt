package io.github.offlineglass.hook.adapters.douyin

import android.graphics.Rect
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.WindowInsets
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Roots returned by the actual profile fragments, before their first layout.
 * Verified against 40.8 ProfilePageFragmentV2 and BasePandaFragment; no resource
 * IDs, label guesses, view-tree searches or per-frame layout listener. */
internal object DouyinProfilePages {
    private val roots = WeakHashMap<View, Boolean>()
    private val visibleRect = Rect()
    private var installed = false
    private var statusWritten = false

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        for (name in arrayOf(
            "com.ss.android.ugc.aweme.profile.ProfilePageFragmentV2",
            "com.ss.android.ugc.aweme.profile.ui.core.BasePandaFragment",
            "com.ss.android.ugc.aweme.profile.ui.core.UserPandaFragment",
        )) {
            runCatching {
                val type = Class.forName(name, false, loader)
                val method = type.getDeclaredMethod("onCreateView", LayoutInflater::class.java,
                    ViewGroup::class.java, android.os.Bundle::class.java)
                require(View::class.java.isAssignableFrom(method.returnType))
                val hook = object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val root = p.result as? ViewGroup ?: return
                        var fragmentType: Class<*>? = p.thisObject.javaClass
                        var ownProfile = false
                        while (fragmentType != null) {
                            if (fragmentType.name == "com.ss.android.ugc.aweme.profile.ui.MyPandaFragment" ||
                                fragmentType.simpleName == "MyPandaFragment") ownProfile = true
                            fragmentType = fragmentType.superclass
                        }
                        // BasePanda creates both MyPanda and UserPanda roots.
                        // Keep both for content ownership, hide only other users.
                        roots[root] = !ownProfile
                        if (!statusWritten) {
                            statusWritten = true
                            runCatching {
                                java.io.File(root.context.getExternalFilesDir(null), "dy_profile_binding_status.txt")
                                    .writeText("time=${System.currentTimeMillis()} fragment=$name root=${root.javaClass.name}\n")
                            }
                        }
                    }
                }
                XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                    .invoke(null, method, hook)
                if (name == "com.ss.android.ugc.aweme.profile.ProfilePageFragmentV2") {
                    val getView = type.getMethod("getView")
                    val getActivity = type.getMethod("getActivity")
                    val sync = object : XC_MethodHook() {
                        override fun afterHookedMethod(p: MethodHookParam) {
                            val page = getView.invoke(p.thisObject) as? View ?: return
                            val activity = getActivity.invoke(p.thisObject) as? Activity ?: return
                            page.post { syncDetailNavigation(page, activity) }
                        }
                    }
                    XposedBridge.hookMethod(type.getDeclaredMethod("onStart"), sync)
                    XposedBridge.hookMethod(type.getDeclaredMethod("onPageSelected", Int::class.javaPrimitiveType), sync)
                }
            }.onFailure {
                XposedBridge.log("[LiquidTab] Douyin profile lifecycle binding unavailable $name: $it")
            }
        }
    }

    private data class NavigationState(val color: Int, val contrast: Boolean, val flags: Int, val fitTypes: Int)
    private val detailNavigation = WeakHashMap<Activity, NavigationState>()

    @Suppress("DEPRECATION")
    private fun syncDetailNavigation(page: View, activity: Activity) {
        val window = activity.window
        val decor = window.decorView
        val pager = page.parent as? ViewGroup ?: return
        val namespace = pager.javaClass.name
        if (roots[page] != true || page.rootView !== decor ||
            (!namespace.startsWith("com.ss.android.ugc.aweme.detail.") &&
                !namespace.startsWith("com.bytedance.ies.ugc.aweme.photos.detail."))) return
        val layoutFlag = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val active = page.isShown && page.getGlobalVisibleRect(visibleRect) &&
            visibleRect.width() > pager.width / 2 &&
            io.github.offlineglass.hook.HookConfigReader.read(activity, activity.packageName).enabled
        if (active) {
            detailNavigation.getOrPut(activity) {
                NavigationState(window.navigationBarColor,
                    Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced,
                    decor.systemUiVisibility, if (Build.VERSION.SDK_INT >= 30) window.attributes.fitInsetsTypes else 0)
            }
            // Extend the content behind the system navigation controls using
            // Android's layout flag. Do not move or hide those controls.
            decor.systemUiVisibility = decor.systemUiVisibility or layoutFlag
            if (Build.VERSION.SDK_INT >= 30) {
                val attrs = window.attributes
                val types = attrs.fitInsetsTypes and WindowInsets.Type.navigationBars().inv()
                if (types != attrs.fitInsetsTypes) {
                    attrs.setFitInsetsTypes(types)
                    window.attributes = attrs
                }
            }
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        } else {
            val original = detailNavigation.remove(activity) ?: return
            if (Build.VERSION.SDK_INT >= 30) {
                val attrs = window.attributes
                val nav = WindowInsets.Type.navigationBars()
                attrs.setFitInsetsTypes((attrs.fitInsetsTypes and nav.inv()) or (original.fitTypes and nav))
                window.attributes = attrs
            }
            decor.systemUiVisibility = (decor.systemUiVisibility and layoutFlag.inv()) or (original.flags and layoutFlag)
            window.navigationBarColor = original.color
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = original.contrast
        }
        decor.requestApplyInsets()
    }

    fun contains(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (roots.containsKey(node)) return true
            node = node.parent as? View
        }
        return false
    }

    fun isVisible(window: View?): Boolean {
        window ?: return false
        for (page in roots.keys) {
            if (roots[page] != true) continue
            // Some versions wrap MyPanda in a generic profile fragment. Its
            // ancestor is not an additional "other profile" overlay.
            var wrapsOwnPage = false
            for ((ownRoot, other) in roots) {
                if (other || !ownRoot.isAttachedToWindow || !ownRoot.isShown) continue
                var parent = ownRoot.parent as? View
                while (parent != null && parent !== window) {
                    if (parent === page) { wrapsOwnPage = true; break }
                    parent = parent.parent as? View
                }
                if (wrapsOwnPage) break
            }
            if (wrapsOwnPage) continue
            if (page.rootView === window && page.isAttachedToWindow && page.isShown &&
                page.alpha > .01f && page.getGlobalVisibleRect(visibleRect) &&
                visibleRect.width() > window.width / 2 && visibleRect.height() > window.height / 2) return true
        }
        return false
    }
}
