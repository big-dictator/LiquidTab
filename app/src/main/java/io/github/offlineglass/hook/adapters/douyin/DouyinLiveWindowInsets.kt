package io.github.offlineglass.hook.adapters.douyin

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassInstaller
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 40.8 classes42: LivePlayActivity owns resume/focus status-bar adaptation,
 * but is outside the main-navigation scanner. Its original 65px navigation
 * fit leaves a full-width system background below the live room.
 * Repair the navigation edge only; no player transform or home-control lift.
 */
internal object DouyinLiveWindowInsets {
    private val observed = WeakHashMap<View, Boolean>()
    fun install(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.ss.android.ugc.aweme.live.LivePlayActivity", false, loader)
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() == false) return
                    val activity = param.thisObject as? Activity ?: return
                    val decor = activity.window?.decorView ?: return
                    update(activity)
                    if (observed.put(decor, true) == true) return
                    val owner = WeakReference(activity)
                    var lastHeight = -1
                    var reports = 0
                    decor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                        override fun onGlobalLayout() {
                            val current = owner.get() ?: return
                            update(current)
                            val content = current.findViewById<View>(android.R.id.content) ?: return
                            if (content.height <= 0 || content.height == lastHeight || reports >= 3) return
                            lastHeight = content.height; reports++
                            val rect = Rect(); content.getGlobalVisibleRect(rect)
                            runCatching { java.io.File(current.getExternalFilesDir(null), "dy_live_insets_status.txt")
                                .writeText("time=${System.currentTimeMillis()} activity=${current.javaClass.name} content=$rect decor=${decor.width}x${decor.height}\n") }
                        }
                    })
                }
            }
            XposedBridge.hookAllMethods(type, "onResume", hook)
            XposedBridge.hookAllMethods(type, "onWindowFocusChanged", hook)
        }.onFailure { XposedBridge.log("[LiquidTab] live navigation binding unavailable: $it") }
    }

    private fun update(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val config = HookConfigReader.read(activity, "com.ss.android.ugc.aweme")
        if (config.enabled && !config.classicNavigation) GlassInstaller.ensureNavigationBarImmersion(activity)
    }
}
