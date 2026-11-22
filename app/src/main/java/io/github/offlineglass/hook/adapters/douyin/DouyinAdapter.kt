package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Douyin 40.3.0: only root_view is an authoritative five-cell navigation row. */
internal object DouyinAdapter : TargetAdapter {
    override fun installBootstrapHooks(param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        DouyinStartupCompatibility.install(param)
    }
    private var layoutCache: io.github.offlineglass.hook.adapters.AdapterBarLayout? = null
    private var layoutWidth = 0
    private var layoutHeight = 0
    private var layoutDensity = 0f
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int, height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || width <= height || height <= 0 || config.hiddenMask and (1 shl 2) != 0) return null
        if (layoutWidth != width || layoutHeight != height || layoutDensity != density || layoutCache == null) {
            val gap = 12f * density
            val actionLeft = width - height.toFloat()
            layoutCache = io.github.offlineglass.hook.adapters.AdapterBarLayout(
                mainWidth = (actionLeft - gap).coerceAtLeast(height.toFloat()),
                actionLeft = actionLeft, actionSize = height.toFloat(), actionIndex = 2,
                navigationIndices = listOf(0, 1, 3, 4),
                localActionOptics = true, localMainOptics = true,
            )
            layoutWidth = width; layoutHeight = height; layoutDensity = density
        }
        return layoutCache
    }
    override fun installHooks(lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        if (lpparam.processName == lpparam.packageName) {
            DouyinProfilePages.install(lpparam.classLoader)
            runCatching { DouyinListPages.install(lpparam.classLoader) }
                .onFailure { io.github.offlineglass.hook.compat.XposedBridge.log("[LiquidTab] list page lifecycle unavailable: $it") }
            DouyinSidebarPages.install(lpparam.classLoader)
            DouyinTopBar.install(lpparam.classLoader)
            DouyinDetailCommentGlass.install(lpparam.classLoader)
            DouyinLiveMaskHooks.install()
            DouyinLiveWindowInsets.install(lpparam.classLoader)
            DouyinLongPressInsets.install(lpparam.classLoader)
            DouyinSeekMaskHooks.install()
            DouyinCommentLayoutHooks.install(lpparam.classLoader)
        }
        DouyinPlayerTextureHooks.install(lpparam)
    }
    override val key = "douyin"
    override val acceptsVersionedStartupPreferences = true
    override fun onConfigurationReady(context: android.content.Context,
        param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        DouyinPlayerTextureHooks.prepare(context)
        if (param.processName == param.packageName) DouyinSplashHooks.prepare(context)
        val snapshot = io.github.offlineglass.hook.HookConfigReader.startupSnapshot(param.packageName) ?: return
        runCatching {
            java.io.File(context.getExternalFilesDir(null), "dy_startup_config_status.txt").writeText(
                "time=${System.currentTimeMillis()} enabled=${snapshot.enabled} liquid=${snapshot.liquidGlassEnabled} " +
                    "detached=${snapshot.detachedPublish} blur=${snapshot.blurRadius}\n")
        }
    }
    override val releaseOutlineClipping = true
    override fun surfaceOpacityScale(config: io.github.offlineglass.config.GlassConfig) = config.shortVideoBarOpacity
    override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int): Int =
        when {
            dark -> defaultColor
            selectedIndex == 0 || selectedIndex == 1 -> android.graphics.Color.argb(36, 33, 31, 38)
            else -> android.graphics.Color.TRANSPARENT
        }
    override val allowRenderNodeRefresh = true
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val drawsNavigationInOptics = true
    override val persistentNavigationOpticalMix = false
    override val foregroundMaskInsetDp = 0f
    override val panelHighlightFollowsAllThemes = true
    override val requestedFrameRateMax = 120f
    override val continuousFramePump = true
    override val opticalIconBandScale = 1.5f
    override val secondaryBlurScale = 0.72f
    override val skipBackdropStableDelay = true
    override val forceCompositorFrames = true
    override val restingOpticalMix = 0.86f
    override val hasPerFrameScene = true
    override val sceneVisibilityAnimation = true
    override fun createNavigationState(context: android.content.Context): AppNavigationState =
        DouyinNavigationState(context)
    override fun createNativeChromeController() = object : io.github.offlineglass.hook.adapters.AppNativeChromeController {
        override fun update(host: View, source: ViewGroup?) {
            source ?: return
            suppressNativeBottomChrome(source)
        }
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val row = AdapterNavigationSearch.findFirst(root) { view ->
            AdapterNavigationSearch.resourceEntryName(view) == "root_view" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width >= root.width * 0.9f && view.height > 0 &&
                view.childCount == 5 &&
                (0 until view.childCount).all { view.getChildAt(it).isClickable }
        }
        return row?.let { NavigationCandidate(it, 5, 1_000) }
    }

    /** Retain the native click controller but remove its full-width chrome. */
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        DouyinBottomDecorations.suppress(navigation)
        navigation.background = null
        val rowShell = navigation.parent as? ViewGroup ?: return
        rowShell.background = null
        for (index in 0 until rowShell.childCount) {
            val child = rowShell.getChildAt(index)
            if (child !== navigation && child.width >= navigation.width * 0.8f) child.alpha = 0f
        }
        val chrome = rowShell.parent as? ViewGroup ?: return
        chrome.background = null
        for (index in 0 until chrome.childCount) {
            val child = chrome.getChildAt(index)
            if (child !== rowShell && child.width >= navigation.width * 0.8f &&
                child.height >= navigation.height * 0.8f
            ) child.alpha = 0f
        }
        (chrome.parent as? ViewGroup)?.background = null

    }
}




