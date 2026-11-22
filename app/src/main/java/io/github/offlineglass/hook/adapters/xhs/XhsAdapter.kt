package io.github.offlineglass.hook.adapters.xhs

import android.content.Context
import android.app.Activity
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Insets
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

internal object XhsAdapter : TargetAdapter {
    override fun installHooks(
        lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) {
        XhsDetailGlass.installHooks()
        XhsNativeBindings.install(lpparam.classLoader)
        XhsVideoRenderHooks.install(lpparam.classLoader)
        XhsVideoViewport.install()
        XhsCommentGlass.install()
        XhsTopTheme.install(lpparam.classLoader)
        XhsTopBar.install(lpparam.classLoader)
        XhsTopNotifications.install(lpparam.classLoader)
        XhsHomeDiagnostics.install()
        XhsHomeFeedEdges.install(lpparam.classLoader, spec.packageName)
    }
    override fun acceptPushedConfig(config: GlassConfig): Boolean {
        XhsHomeFeedEdges.configChanged(config)
        return false
    }
    override val key = "xhs"
    override val useBoundedContourSearch = true
    override val ownsNavigationDrawing = true
    // Both optical passes use XhsNavigationRenderer. The invisible native row
    // is retained for actions/state, never used as an artwork bitmap.
    override val skipNavigationSnapshot = true
    // Compound geometry receives two renderer-owned shadows, never the platform
    // elevation silhouette spanning the navigation/action gap.
    override val suppressPlatformElevation = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override val foregroundMaskInsetDp = 0f
    private var cachedBarLayout: io.github.offlineglass.hook.adapters.AdapterBarLayout? = null
    private var cachedBarWidth = 0
    private var cachedBarHeight = 0
    private var cachedBarDensity = 0f
    private var cachedBarMask = -1
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int,
        height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || config.hiddenMask and 4 != 0 ||
            height <= 0 || width <= height + 12f * density) return null
        if (width == cachedBarWidth && height == cachedBarHeight && density == cachedBarDensity &&
            config.hiddenMask == cachedBarMask) return cachedBarLayout
        val navigation = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = width - height.toFloat()
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = actionLeft - 12f * density, actionLeft = actionLeft,
            actionSize = height.toFloat(), actionIndex = 2, navigationIndices = navigation,
            actionHighlightScale = 0.35f,
        ).also {
            cachedBarLayout = it
            cachedBarWidth = width; cachedBarHeight = height
            cachedBarDensity = density; cachedBarMask = config.hiddenMask
        }
    }
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val usesCommittedSelection = true
    override val usesContentDarkMode = true
    override val hasPerFrameScene = true
    override fun scrollStopHideTab(selectedIndex: Int): Boolean = false
    override fun resetScrollStopHideOnOtherTabs(): Boolean = true
    override fun isBackPeekTab(selectedIndex: Int): Boolean = false
    override fun isBackPeekActivity(activity: Activity): Boolean = activity.javaClass.name in MAIN_TAB_ACTIVITIES
    override val backPeekDurationMs = 1500L
    override fun createNavigationState(context: Context): AppNavigationState = XhsNavigationState()

    override fun prepareContentForInstall(content: ViewGroup) {
        // Remove only navigation fitting. Keep status/cutout and IME insets intact.
        content.setOnApplyWindowInsetsListener { _, insets ->
            val type = WindowInsets.Type.navigationBars()
            val navigation = insets.getInsets(type)
            WindowInsets.Builder(insets)
                .setInsets(type, Insets.of(navigation.left, navigation.top, navigation.right, 0))
                .setInsetsIgnoringVisibility(type, Insets.of(navigation.left, navigation.top, navigation.right, 0))
                .build()
        }
        content.requestApplyInsets()
    }

    private val MAIN_TAB_ACTIVITIES = setOf(
        "com.xingin.xhs.index.v2.IndexActivityV2",
        "com.xingin.xhs.IndexActivity",
        "com.xingin.xhs.activity.LauncherActivity",
    )
}
