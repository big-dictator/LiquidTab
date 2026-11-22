package io.github.offlineglass.hook.adapters.bilibili_in

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowInsets
import android.graphics.Insets
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.bilibili.BilibiliHomeFeedEdges
import io.github.offlineglass.hook.adapters.bilibili.BilibiliSearchFeedback
import io.github.offlineglass.hook.adapters.bilibili.twoTabCompoundWidth
import io.github.offlineglass.targets.TargetSpec
import io.github.offlineglass.config.GlassConfig

/** International Bilibili's tab_host is Compose-backed, not a row of child Views. */
internal object BilibiliInAdapter : TargetAdapter {
    override val key = "bilibili_in"
    override val homeOnlyNavigation: Boolean get() = io.github.offlineglass.BuildConfig.BETA_BUILD
    override fun installHooks(lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        if (lpparam.processName == "$PACKAGE_NAME:web") {
            if (!io.github.offlineglass.BuildConfig.BETA_BUILD) {
                io.github.offlineglass.hook.adapters.bilibili.BilibiliWebInsetsHooks.install(packageName = PACKAGE_NAME)
            }
            return
        }
        runCatching { BilibiliInSplashHooks.install(lpparam.classLoader) }.onFailure { XposedBridge.log("LiquidTab international splash: $it") }
        if (!io.github.offlineglass.BuildConfig.BETA_BUILD) {
            runCatching { io.github.offlineglass.hook.adapters.bilibili.BilibiliPlaylistGlassHooks.install(PACKAGE_NAME) }
                .onFailure { XposedBridge.log("LiquidTab international playlist glass: $it") }
            runCatching { io.github.offlineglass.hook.adapters.bilibili.BilibiliWebInsetsHooks.install(authorSpace = true, packageName = PACKAGE_NAME) }
                .onFailure { XposedBridge.log("LiquidTab international author space: $it") }
            runCatching { BilibiliInVideoAmbient.install(lpparam.classLoader) }.onFailure { XposedBridge.log("LiquidTab international video ambient: $it") }
            runCatching { BilibiliInCommentHooks.install(lpparam.classLoader) }.onFailure { XposedBridge.log("LiquidTab international comments: $it") }
            runCatching { BilibiliInHomeLayoutHooks.install(lpparam.classLoader) }.onFailure { XposedBridge.log("LiquidTab international home install: $it") }
            runCatching { BilibiliHomeFeedEdges.install(lpparam.classLoader, PACKAGE_NAME) }.onFailure { XposedBridge.log("LiquidTab international home feed edges: $it") }
            runCatching { BilibiliInSpeedHaptics.install() }.onFailure { XposedBridge.log("LiquidTab international speed haptics: $it") }
        }
    }
    override val ownsNavigationFinding = true
    override val nativeSelectionReliable: Boolean = false
    const val PACKAGE_NAME = "com.bilibili.app.in"
    const val ACCENT_COLOR = 0xFFFB7299.toInt()
    const val SOURCE_ICON_SCALE = 1.15f
    const val SOURCE_SPACING_DP = 2f
    const val SOURCE_GROUP_OFFSET_DP = 2f
    override val sourceIconScale: Float = SOURCE_ICON_SCALE
    override val sourceTextTranslationDp: Float = SOURCE_SPACING_DP + SOURCE_GROUP_OFFSET_DP
    override val sourceIconTranslationDp: Float = -SOURCE_SPACING_DP + SOURCE_GROUP_OFFSET_DP
    override fun hookSignals() = io.github.offlineglass.hook.adapters.bilibili_in.hookSignals()
    @Volatile private var latestPushedConfig: GlassConfig? = null
    override fun pushedConfig(): GlassConfig? = latestPushedConfig
    override fun acceptPushedConfig(config: GlassConfig): Boolean {
        latestPushedConfig = config.normalized()
        BilibiliHomeFeedEdges.configChanged(PACKAGE_NAME, config)
        return true
    }
    override val foregroundNavigationOverSelection = true
    override fun compactHostWidth(config: GlassConfig, density: Float, height: Int, tabRowWidth: (Int) -> Int) =
        twoTabCompoundWidth(config, 4, density, height, tabRowWidth)
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val suppressPlatformElevation = true
    override val recheckNavigationBindingOnLayout = true
    override fun barLayout(config: GlassConfig, width: Int, height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.bilibiliTopBar || !config.bilibiliInDetachedSearch || slots != 4 || height <= 0 || width <= height+12*density) return null
        val left = width-height.toFloat()
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = left-12*density, actionLeft = left, actionSize = height.toFloat(), actionIndex = 4,
            navigationIndices = (0..3).filter { config.hiddenMask and (1 shl it) == 0 },
        )
    }
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override fun createNavigationState(context: android.content.Context): io.github.offlineglass.hook.adapters.AppNavigationState =
        object : io.github.offlineglass.hook.adapters.AppNavigationState {
            private val searchFeedback = BilibiliSearchFeedback()
            override fun actionButtonPressProgress() = searchFeedback.progress
            override fun interceptNavigationTouch(host: View, event: MotionEvent): Boolean? {
                val glass = host as? io.github.offlineglass.hook.GlassHostLayout ?: return null
                searchFeedback.touch(host, event, barLayout(glass.config, host.width,
                    host.height, host.resources.displayMetrics.density, glass.adapterSlotCount) != null)
                return null
            }
            override fun onHostDetached() = searchFeedback.reset()
            override fun onHostWindowFocusChanged(hasFocus: Boolean) { if (!hasFocus) searchFeedback.reset() }
            override fun drawPanelEdgeGlow(canvas: Canvas, path: android.graphics.Path, density: Float) =
                searchFeedback.drawLight(canvas, path)
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
                if (index == 4) {
                    // This action is not a fifth native page and must never commit tab selection.
                    BilibiliInHomeLayoutHooks.openSearch(host.rootView)
                    return false
                }
                return dispatchTabTap(source, index, slotCount)
            }
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = drawNavigation(canvas, frame, searchFeedback.progress)
            override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
            override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
                selectionPath: android.graphics.Path, drawSource: () -> Unit) =
                io.github.offlineglass.hook.adapters.bilibili.BilibiliOpticalForeground.draw(
                    canvas, frame, selectionPath, drawSource)
        }
    override val ownsNavigationDrawing = true
    override val usesCommittedSelection = true
    override val usesContentDarkMode = true
    override val sceneVisibilityAnimation = true
    override val drawsNavigationInOptics = true
    override val suppressSourceOnHostRebind = true
    override val accentColor: Int = ACCENT_COLOR
    override fun configureNavigationInsets(decor: View, enabled: Boolean) = configureInsets(decor, enabled)
    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = drawNavigation(canvas, frame, 0f)
    private fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame, searchPress: Float) {
        BilibiliInRenderer.draw(
        canvas, frame.extraScale, frame.slotCount, frame.width, frame.height, frame.slotWidth,
        frame.density, frame.iconScale, frame.textSize, frame.scaledDensity,
        frame.selectedIndex, frame.enabledIndices, frame.iconOnly, frame.darkGlass, frame.hideSelectedForOptics,
        frame.managerEnglishLabels,
        )
        if (!frame.hideSelectedForOptics) frame.barLayout?.let { layout ->
            val saved = canvas.save()
            val scale = 1f + .08f * searchPress
            canvas.scale(scale, scale, layout.actionLeft+layout.actionSize/2, frame.height/2f)
            BilibiliInHomeLayoutHooks.drawSearchGlyph(canvas, layout.actionLeft+layout.actionSize/2,
                frame.height/2f, frame.density*1.15f, frame.darkGlass)
            canvas.restoreToCount(saved)
        }
    }
    override fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean =
        dispatchTabTap(source, index, slotCount)
    override fun suppressNativeBottomChrome(navigation: ViewGroup) = suppressNativeBottomBar(navigation)
    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) = suppressNativeBottomBar(navigation)

    /** Compose's hidden row has no reliable View-level selected state. */
    fun resolvedSelectedIndex(committedIndex: Int, slotCount: Int): Int =
        committedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

    /** No publish/shop tab: the universal veil is eligible on all four pages. */
    fun allowsUniversalVeil(): Boolean = true

    override fun immersionWasReset(immersiveStub: Any?): Boolean = immersiveStub != null &&
        runCatching { XposedHelpers.callMethod(immersiveStub, "isForceImmersive") as? Boolean }
            .getOrNull() == false

    private val edgeToEdgeWindows = WeakHashMap<View, Boolean>()
    private var insetsHookInstalled = false

    /** Only the international Compose tab host needs its navigation inset removed. */
    fun configureInsets(decor: View, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (enabled) edgeToEdgeWindows[decor] = true else edgeToEdgeWindows.remove(decor)
        if (insetsHookInstalled || !enabled) return
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchApplyWindowInsets",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (edgeToEdgeWindows[param.thisObject] != true) return
                    val original = param.args.firstOrNull() as? WindowInsets ?: return
                    val type = WindowInsets.Type.navigationBars()
                    val visible = original.getInsets(type)
                    val stable = original.getInsetsIgnoringVisibility(type)
                    if (visible.bottom == 0 && stable.bottom == 0) return
                    param.args[0] = WindowInsets.Builder(original)
                        .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
                        .setInsetsIgnoringVisibility(type,
                            Insets.of(stable.left, stable.top, stable.right, 0))
                        .build()
                }
            })
        insetsHookInstalled = true
    }

    fun suppressNativeBottomBar(source: ViewGroup?) {
        if (source == null) return
        if (source.alpha != 0f) source.alpha = 0f
        if (source.background != null) source.background = null
    }

    /** Compose's pointer input consumes the synthesized native tab tap. */
    fun dispatchTabTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (source == null || !source.isAttachedToWindow || source.width <= 0 || slotCount <= 0) return false
        val x = source.width * (index + 0.5f) / slotCount
        val y = source.height / 2f
        val now = SystemClock.uptimeMillis()
        listOf(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(now, now + 48L, MotionEvent.ACTION_UP, x, y, 0),
        ).forEach { event ->
            runCatching { source.dispatchTouchEvent(event) }
            event.recycle()
        }
        return true
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            (view.javaClass.name == WINDOW_AWARE_TAB_HOST ||
                classHierarchyContains(view.javaClass, COMPOSE_VIEW)) &&
                AdapterNavigationSearch.resourceEntryName(view) == "tab_host" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        } ?: return null
        return NavigationCandidate(navigation, spec.preferredSlots.first.coerceIn(2, 7), 1_000)
    }

    private fun classHierarchyContains(initial: Class<*>, name: String): Boolean {
        var type: Class<*>? = initial
        while (type != null) {
            if (type.name == name) return true
            type = type.superclass
        }
        return false
    }

    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"
    private const val WINDOW_AWARE_TAB_HOST =
        "tv.danmaku.bili.home.components.bottomtab.WindowAwareComposeView"
}
