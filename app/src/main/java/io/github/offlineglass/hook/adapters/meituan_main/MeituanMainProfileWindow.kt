package io.github.offlineglass.hook.adapters.meituan_main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.view.Window
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.GlassInstaller

/** Mine must fill the same physical window as cart; safe padding belongs to its list. */
internal class MeituanMainProfileWindow {
    private var window: Window? = null
    private var savedFlags = 0
    private var savedImmersive: Boolean? = null
    private var stub: Any? = null
    private var applied = false
    private val navFlags = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

    fun update(host: GlassHostLayout, active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (!active) { restore(); return }
        var context: Context = host.context
        while (context !is Activity && context is ContextWrapper) {
            val next = context.baseContext
            if (next === context) return
            context = next
        }
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        GlassInstaller.ensureNavigationBarImmersion(activity)
        val target = activity.window
        val decor = target.decorView
        if (window !== target) {
            restore()
            window = target
            savedFlags = decor.systemUiVisibility
            stub = runCatching { XposedHelpers.callMethod(decor, "getDecorViewImmersiveStub") }.getOrNull()
            savedImmersive = stub?.let {
                runCatching { XposedHelpers.callMethod(it, "isForceImmersive") as? Boolean }.getOrNull()
            }
        }
        val forced = stub?.let {
            runCatching { XposedHelpers.callMethod(it, "isForceImmersive") as? Boolean }.getOrNull()
        }
        val current = decor.systemUiVisibility
        val layout = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val desired = (current and navFlags.inv()) or layout
        if (applied && current == desired && forced != false) return
        // Opt out of consuming the navigation inset. Fitting it into the
        // window reserved 65px on this device and raised the entire glass row.
        target.setDecorFitsSystemWindows(false)
        // Preserve status-bar layout/fullscreen and unrelated flags.
        decor.systemUiVisibility = desired
        stub?.let {
            runCatching {
                XposedHelpers.callMethod(it, "setForceImmersiveNavBar", true)
                XposedHelpers.callMethod(decor, "updateColorViews", null, false)
            }
        }
        applied = true
        decor.requestApplyInsets()
    }

    fun restore() {
        window?.let { target ->
            val decor = target.decorView
            val current = decor.systemUiVisibility
            target.setDecorFitsSystemWindows(savedFlags and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION == 0)
            decor.systemUiVisibility = (current and navFlags.inv()) or (savedFlags and navFlags)
            stub?.let {
                runCatching {
                    XposedHelpers.callMethod(it, "setForceImmersiveNavBar", savedImmersive ?: false)
                    XposedHelpers.callMethod(decor, "updateColorViews", null, false)
                }
            }
            decor.requestApplyInsets()
        }
        window = null
        stub = null
        savedImmersive = null
        applied = false
    }
}
