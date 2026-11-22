package io.github.offlineglass.hook.adapters.tieba

import android.app.Activity
import android.util.TypedValue
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.AdapterBarLayout
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import android.content.Context
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.targets.TargetSpec

/** Tieba-only native chrome and feed prompt hooks. */
internal object TiebaAdapter : TargetAdapter {
    override val key = "tieba"
    const val TAB_ICON_SCALE = 1.15f
    override val sourceIconScale = TAB_ICON_SCALE
    override val sourceTextTranslationDp = -0.75f
    override fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        return true
    }
    override val projectedNavigationScale = 1.10f
    override val foregroundNavigationOverSelection = true
    override val ownsNavigationDrawing = true
    override val usesNavigationOpticalMix = true
    override val drawsNavigationInOptics = true
    override val foregroundMaskInsetDp = 0f
    override val suppressPlatformElevation = true
    override val ownsNavigationSnapshotCapturePolicy = true
    override fun barLayout(config: GlassConfig, width: Int, height: Int, density: Float, slots: Int): AdapterBarLayout? {
        val gap = 12f * density
        if (!config.detachedPublish || !config.isTiebaPublishVisible() || slots != 5 ||
            height <= 0 || width <= height + gap) return null
        val navigation = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = (width - height).toFloat()
        return AdapterBarLayout(
            mainWidth = actionLeft - gap,
            actionLeft = actionLeft,
            actionSize = height.toFloat(),
            actionIndex = 2,
            navigationIndices = navigation,
            actionFillColor = TIEBA_PUBLISH_BLUE,
            actionHighlightScale = 0.35f,
            localActionOptics = true,
            localMainOptics = true,
            actionFillUsesOnlySurfaceOpacity = true,
        )
    }
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        TiebaNavigationState(host)
    private const val TIEBA_PACKAGE = "com.baidu.tieba"
    private const val SIDEBAR_ACTIVITY = "com.baidu.tieba.sidebar.SideBarAssistActivity"
    @Volatile private var sidebarVisible = false

    override fun isBlockingOverlayActivity(activity: Activity) =
        activity.packageName == TIEBA_PACKAGE && activity.javaClass.name == SIDEBAR_ACTIVITY

    override fun setBlockingOverlayVisible(visible: Boolean) {
        sidebarVisible = visible
        TiebaTopBar.sidebarVisibilityChanged()
    }
    override fun isBlockingOverlayVisible() = sidebarVisible
    override fun beforeNavigationScan(activity: Activity) = TiebaTopBar.scan(activity)

    override fun prepareContentForInstall(content: ViewGroup) {
        if (android.os.Build.VERSION.SDK_INT < 21) return
        content.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.systemWindowInsetBottom
            if (bottom in 1..200) {
                insets.replaceSystemWindowInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    0,
                )
            } else {
                insets
            }
        }
    }
    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        TiebaTopBar.install(lpparam.classLoader)
        TiebaFeedBottomInsets.install(lpparam.classLoader)
        TiebaNativeTabAnimation.install(lpparam.classLoader)
        TiebaSplashAds.install(lpparam.classLoader)
        installTiebaNavigationBarBgSuppression(lpparam)
        installTiebaFeedPromptSuppression(lpparam)
    }

    /** Prevent Tieba from restoring its native tab-row background. */
    private fun installTiebaNavigationBarBgSuppression(
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val candidates = listOf(
            "com.baidu.tbadk.core.tabHost.FragmentTabWidget",
            "com.baidu.tieba.tbui.widget.FragmentTabWidget",
            "com.baidu.tieba.widget.FragmentTabWidget",
        )
        var hooked: Class<*>? = null
        for (name in candidates) {
            hooked = runCatching {
                XposedHelpers.findClass(name, lpparam.classLoader)
            }.getOrNull()
            if (hooked != null) break
        }
        val widgetClass = hooked ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                widgetClass,
                "setNavigationBarBg",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                },
            )
            XposedBridge.log("[OfflineGlass][Tieba] setNavigationBarBg suppressed on ${widgetClass.name}")
        }
    }

    /** Route the recommendation through Tieba's own hide branch, which hides its whole container. */
    private fun installTiebaFeedPromptSuppression(lpparam: XC_LoadPackage.LoadPackageParam) {
        val controller = runCatching {
            XposedHelpers.findClass(
                "com.baidu.tieba.homepage.personalize.controller.FeedPromptBarController",
                lpparam.classLoader,
            )
        }.getOrNull() ?: return
        runCatching {
            XposedBridge.hookAllMethods(
                controller,
                "k",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size == 1 && param.args[0] == true) param.args[0] = false
                    }
                },
            )
            XposedBridge.log("[OfflineGlass][Tieba] FeedPromptBarController visibility hook installed")
        }.onFailure {
            XposedBridge.log("[OfflineGlass][Tieba] FeedPromptBarController hook failed: $it")
        }
    }

}
