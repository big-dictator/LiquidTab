package io.github.offlineglass.hook.adapters.tiktok

import android.app.Activity
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassInstaller
import java.util.WeakHashMap

/** TikTok windows receive zero navigation-bottom fitting, retaining status and IME insets. */
internal object TikTokInsets {
    private val windows = WeakHashMap<View, Boolean>()
    private data class StatusState(val flags: Int, val color: Int)
    private val status = WeakHashMap<View, StatusState>()
    private var installed = false
    fun setFeedStatus(host: View, enabled: Boolean) {
        val decor = host.rootView
        val activity = generateSequence(host.context as? android.content.Context) {
            (it as? android.content.ContextWrapper)?.baseContext?.takeIf { next -> next !== it }
        }.take(12).filterIsInstance<Activity>().firstOrNull() ?: return
        if (activity.packageName != "com.zhiliaoapp.musically") return
        val window = activity.window
        val flag = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        var changed = false
        if (enabled) {
            if (!status.containsKey(decor)) {
                window.setDecorFitsSystemWindows(false)
                changed = true
            }
            status.getOrPut(decor) { StatusState(decor.systemUiVisibility, window.statusBarColor) }
            if (decor.systemUiVisibility and flag != flag) {
                decor.systemUiVisibility = decor.systemUiVisibility or flag; changed = true
            }
            if (window.statusBarColor != android.graphics.Color.TRANSPARENT) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                changed = true
            }
        } else {
            val saved = status.remove(decor) ?: return
            window.setDecorFitsSystemWindows(true)
            decor.systemUiVisibility = (decor.systemUiVisibility and flag.inv()) or (saved.flags and flag)
            window.statusBarColor = saved.color
            changed = true
        }
        if (changed) decor.requestApplyInsets()
    }
    fun configure(decor: View, enabled: Boolean) {
        if (enabled) windows[decor] = true else windows.remove(decor)
        install()
    }
    fun install() {
        if (installed) return
        installed = true
        // Native startup/page callbacks can opt back into fitting after the
        // first feed frame. Keep the same ownership through those callbacks.
        XposedBridge.hookAllMethods(android.view.Window::class.java, "setDecorFitsSystemWindows", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val window = param.thisObject as? android.view.Window ?: return
                val decor = window.peekDecorView() ?: return
                if (status.containsKey(decor)) param.args[0] = false
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setSystemUiVisibility", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val decor = param.thisObject as? View ?: return
                if (!status.containsKey(decor)) return
                val flags = param.args.firstOrNull() as? Int ?: return
                param.args[0] = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        })
        // Hook only the window's native inset application. Hooking the inherited
        // ViewGroup dispatcher intercepts hundreds of descendants on every video
        // frame (47.1.4 trace: ~35ms dispatchApplyInsets per traversal).
        val decorType = Class.forName("com.android.internal.policy.DecorView")
        decorType.getDeclaredMethod("onApplyWindowInsets", WindowInsets::class.java)
        XposedBridge.hookAllMethods(decorType, "onApplyWindowInsets", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (windows[param.thisObject] != true) return
                val original = param.args.firstOrNull() as? WindowInsets ?: return
                val nav = WindowInsets.Type.navigationBars()
                val visible = original.getInsets(nav)
                val stable = original.getInsetsIgnoringVisibility(nav)
                val builder = WindowInsets.Builder(original)
                    .setInsets(nav, Insets.of(visible.left, visible.top, visible.right, 0))
                    .setInsetsIgnoringVisibility(nav, Insets.of(stable.left, stable.top, stable.right, 0))
                if (status.containsKey(param.thisObject as View)) {
                    val top = WindowInsets.Type.statusBars()
                    builder.setInsets(top, Insets.NONE).setInsetsIgnoringVisibility(top, Insets.NONE)
                }
                param.args[0] = builder.build()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName == "com.zhiliaoapp.musically")
                    GlassInstaller.updateAdapterNavigationBarForPage(activity)
            }
        })
    }
}
