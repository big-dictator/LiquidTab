package io.github.offlineglass.hook.adapters.xianyu

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.GlassHostLayout

/** Xianyu owns its chrome, projection and click policy in this workspace. */
internal object XianyuAdapter : TargetAdapter {
    override val key = "xianyu"
    override fun installHooks(
        lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) { XianyuSplashHooks.install(lpparam.classLoader) }
    override val nativeSelectionReliable = false
    override val forceLightMode = true
    override val disablePlatformForceDark = true
    override val ownsNavigationDrawing = true
    override val skipNavigationSnapshot = true
    override val suppressPlatformElevation = true
    override val continuousIndicatorMask = true
    override val observeWindowTouches = true
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int,
        height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || config.hiddenMask and 4 != 0 ||
            height <= 0 || width <= height + 12f * density) return null
        val navigation = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = width - height.toFloat()
        // The glass owns the yellow material, so opacity follows the same
        // bottom-bar setting instead of being baked into the native artwork.
        val alpha = android.graphics.Color.alpha(config.surfaceTintColor(false))
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = actionLeft - 12f * density, actionLeft = actionLeft,
            actionSize = height.toFloat(), actionIndex = 2, navigationIndices = navigation,
            actionFillColor = android.graphics.Color.argb(alpha, 255, 205, 0),
            localActionOptics = true,
            localMainOptics = true,
        )
    }
    override fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        val glass = host as? GlassHostLayout ?: return
        if (glass.adapterSelectedIndex == 3) {
            (glass.appNavigationState as? XianyuNavigationState)?.onMessageTouch(event)
        }
    }
    override fun createNavigationState(context: Context, host: GlassHostLayout) = XianyuNavigationState(host)

    override fun suppressNativeBottomChrome(host: View, navigation: ViewGroup) {
        // The exact shell owns the background image, divider and publish action.
        // Preserve all artwork and handlers; suppress only the shell's pixels.
        val shell = XianyuNavigationState.shell(navigation)
        if (shell.alpha != 0f) shell.alpha = 0f
        if (shell.elevation != 0f) shell.elevation = 0f
        if (shell.translationZ != 0f) shell.translationZ = 0f
        // Backdrop recording can draw a child independently of ancestor alpha.
        // Clear only named navigation surfaces; never clear tab/post artwork.
        for (id in arrayOf("home_indicator_root", "indicator_item_container", "indicator_bg", "indicator_itmes")) {
            XianyuNavigationState.find(shell, id)?.let {
                if (it.background != null) it.background = null
                if (it.foreground != null) it.foreground = null
            }
        }
    }

    override fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        source ?: return false
        val shell = XianyuNavigationState.shell(source)
        val target = if (index == 2) XianyuNavigationState.find(shell, "post_wrapper") else {
            val row = XianyuNavigationState.find(shell, "indicator_itmes") as? ViewGroup
            row?.let { if (index in 0 until it.childCount) it.getChildAt(index) else null }
        } ?: return false
        return click(target)
    }

    private fun click(view: View): Boolean {
        if (view.hasOnClickListeners()) return view.performClick()
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (click(view.getChildAt(i))) return true
        }
        return false
    }
}
