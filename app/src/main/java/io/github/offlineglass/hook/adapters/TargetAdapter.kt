package io.github.offlineglass.hook.adapters

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.adapters.amap.AmapAdapter
import io.github.offlineglass.hook.adapters.hellobike.HelloBikeAdapter
import io.github.offlineglass.hook.adapters.mi_community.MiCommunityAdapter
import io.github.offlineglass.hook.adapters.xhs.XhsAdapter
import io.github.offlineglass.hook.adapters.douyin.DouyinAdapter
import io.github.offlineglass.hook.adapters.bilibili_in.BilibiliInAdapter
import io.github.offlineglass.hook.adapters.bilibili.BilibiliAdapter
import io.github.offlineglass.hook.adapters.meituan.MeituanTakeoutAdapter
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainAdapter
import io.github.offlineglass.hook.adapters.mi_market.MiMarketAdapter
import io.github.offlineglass.hook.adapters.mi_file_manager.FileManagerAdapter
import io.github.offlineglass.hook.adapters.mi_health.MiHealthAdapter
import io.github.offlineglass.hook.adapters.mihome.MiHomeAdapter
import io.github.offlineglass.hook.adapters.netease.NeteaseAdapter
import io.github.offlineglass.hook.adapters.pdd.PddAdapter
import io.github.offlineglass.hook.adapters.taobao.TaobaoAdapter
import io.github.offlineglass.hook.adapters.tieba.TiebaAdapter
import io.github.offlineglass.hook.adapters.wechat.WeChatAdapter
import io.github.offlineglass.hook.adapters.weibo.WeiboAdapter
import io.github.offlineglass.hook.adapters.youtube.YouTubeAdapter
import io.github.offlineglass.hook.adapters.xianyu.XianyuAdapter
import io.github.offlineglass.hook.adapters.cainiao.CainiaoAdapter
import io.github.offlineglass.hook.adapters.jd.JdAdapter
import io.github.offlineglass.hook.adapters.qqmusic.QqMusicAdapter
import io.github.offlineglass.hook.adapters.spotify.SpotifyAdapter
import io.github.offlineglass.hook.adapters.apple_music.AppleMusicAdapter
import io.github.offlineglass.hook.adapters.xjtu.XjtuAdapter
import io.github.offlineglass.hook.adapters.chatgpt.ChatGptAdapter
import io.github.offlineglass.hook.adapters.x.XAdapter
import io.github.offlineglass.hook.adapters.instagram.InstagramAdapter
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.targets.TargetSpec
import io.github.offlineglass.targets.TargetSpecRegistry
import io.github.offlineglass.targets.HookSignal
import io.github.offlineglass.config.GlassConfig

/** The app-owned part of discovery and lifecycle. Shared glass rendering stays in the host. */
internal interface TargetAdapter {
    /** Common CPU work reuse; no sampling/quality/cadence changes. */
    val reuseExactOpticalWork: Boolean get() = true
    /** Global accepted optical return: continuous background and complete resting handoff. */
    val completeIndicatorRestingHandoff: Boolean get() = true
    /** Optional application-owned compatibility setup before Application.attach. */
    fun installBootstrapHooks(param: XC_LoadPackage.LoadPackageParam) {}
    /** The target exposes a native English locale that the manager language may control. */
    val supportsNativeEnglishLocale: Boolean get() = false
    /** Run config-dependent early setup only after a verified startup snapshot. */
    fun onConfigurationReady(context: Context, param: XC_LoadPackage.LoadPackageParam) {}
    /** Legacy diagnostic opt-in; validated startup preferences are now shared by all targets. */
    val acceptsVersionedStartupPreferences: Boolean get() = false
    /** Opt-in experimental posture-driven optics; zero preserves existing rendering. */
    val tiltRefractionStrength: Float get() = io.github.offlineglass.rendering.PostureGlass.STRENGTH
    /** Shared Flowing Liquid policy; adapters only declare temporary exclusions. */
    val flowingLiquidEnabled: Boolean get() = true
    /** Accepted motion policy follows Flowing Liquid, including its exclusions. */
    val liquidMotionEnabled: Boolean get() = flowingLiquidEnabled
    val unifiedDropletPilot: Boolean get() = false
    val directionalRimPilot: Boolean get() = false
    val outerRefractionScale: Float get() = io.github.offlineglass.rendering.PostureGlass.REFRACTION_SCALE
    val tiltShadowStrength: Float get() = io.github.offlineglass.rendering.PostureGlass.SHADOW_STRENGTH
    fun barLayout(config: GlassConfig, width: Int, height: Int, density: Float, slots: Int): AdapterBarLayout? = null
    /** Adapter policy applied to drawing, optics and input through the same index mapping. */
    fun isNavigationEntryDisabled(index: Int, slots: Int): Boolean = false
    val key: String
    /** Opt-in: apply the outer dynamic-highlight setting in either theme. */
    val panelHighlightFollowsAllThemes: Boolean get() = false
    /** Opt-in for the main panel and its sampled copy, never the action or indicator rim. */
    val featheredInnerPanelHighlight: Boolean get() = true
    /** Exact contour-search pilot; unrelated to material/animation settings. */
    val useBoundedContourSearch: Boolean get() = false
    val ownsNavigationFinding: Boolean get() = false
    /** Recover when startup or home reconstruction replaces an already installed source. */
    val recheckNavigationBindingOnLayout: Boolean get() = false

    /** Restore overlay ordering when the app appends home content after startup. */
    val keepNavigationOverlayOnTop: Boolean get() = false
    /** Null keeps the legacy generic detector; an app may explicitly opt out. */
    val nativeSelectionReliable: Boolean? get() = null
    /** Keep host traversal alive when a static page feeds a compositor surface. */
    fun needsContinuousOpticalSurfaceFrames(selectedIndex: Int): Boolean = false
    /** Native source artwork adjustments, owned by each app's adapter. */
    val sourceIconScale: Float? get() = null
    val sourceTextTranslationDp: Float? get() = null
    val sourceIconTranslationDp: Float? get() = null
    val sourceContentOffsetDp: Float get() = 0f
    /** Optional native source label size applied before glass projection. */
    val sourceTextSizeSp: Float? get() = null
    /** Optional scale used when projecting native artwork into the glass row. */
    val projectedNavigationScale: Float get() = 1f
    val ownsNavigationDrawing: Boolean get() = false
    /** Draw projected native artwork before the liquid indicator so the lens refracts it. */
    val navigationBehindIndicator: Boolean get() = false
    val ownsNavigationTap: Boolean get() = false
    /** Main-navigation-only cold scheduling; adapters exclude non-page accessories. */
    fun isFirstVisitPage(source: ViewGroup, index: Int, slots: Int): Boolean = true
    /** Compound accessories may maintain their sibling order as one group. */
    val managesHostSiblingOrder: Boolean get() = false
    val commitSelectionAfterTap: Boolean get() = false
    val skipNavigationSnapshot: Boolean get() = false
    val stagedNavigationSnapshot: Boolean get() = false
    val ownsNavigationSnapshotCapturePolicy: Boolean get() = false
    val supportsScheduledNavigationSnapshotRefresh: Boolean get() = false
    val navigationSnapshotStableFrames: Int? get() = null
    val navigationSignatureMaxDepth: Int get() = 5
    val redrawSurfaceAfterNavigationCapture: Boolean get() = false
    val usesCommittedSelection: Boolean get() = false
    val usesContentDarkMode: Boolean get() = false
    val forceLightMode: Boolean get() = false
    val ownsHostNativeChrome: Boolean get() = false
    /** Some adapters use a native source as a visible content host (e.g. mini-player pages). */
    val keepNavigationSourceVisible: Boolean get() = false
    val hostLiftDp: Float? get() = null
    /** Optional width reference; actual tab count and hit slots stay unchanged. */
    fun hostWidthSlotCount(slots: Int, config: GlassConfig): Int = slots
    /** Optional compound width built from the standard tab-row widths. */
    fun compactHostWidth(
        config: GlassConfig, density: Float, height: Int, tabRowWidth: (Int) -> Int,
    ): Int? = null
    val hasPerFrameScene: Boolean get() = false
    /** Null keeps the normal immersive fitting; an app may pin its own navigation inset. */
    val navigationImmersionOverride: Boolean? get() = null
    /** Reapply the saved non-immersive state if an app rewrites window flags after launch. */
    val repeatImmersionRestoreWhileInactive: Boolean get() = false
    val suppressPlatformElevation: Boolean get() = false
    val contentAncestorMaxDepth: Int? get() = null
    val sourceParentVisibilityOnly: Boolean get() = false
    val sourceSwapGraceMs: Long get() = 0L
    val panelShadowBlurDp: Float? get() = null
    /** Local panel shadow opacity; leaves glass tint and scene content unchanged. */
    val panelShadowAlphaScale: Float get() = 1f
    val synchronousConfigPolling: Boolean get() = false
    val nativeNavigationDrawsInSourceSpace: Boolean get() = false
    val indicatorInnerShadow: Boolean get() = false
    val continuousIndicatorMask: Boolean get() = true
    /** Scale only the slider's border inset; tab hit slots and panel geometry stay shared. */
    val indicatorInsetScale: Float get() = 1f
    /** Opt-in pilot: bounded vertical finger follow on the primary bar only. */
    val indicatorDragVerticalLimitDp: Float get() = 0f
    /** Optional local tint gain; default preserves the shared resting material. */
    fun restingIndicatorTintScale(config: io.github.offlineglass.config.GlassConfig): Float = 1f
    /** Shared slider curve; the visible outer panel is unchanged. */
    val indicatorCornerSmoothing: Float? get() = io.github.offlineglass.rendering.BottomBarIndicatorStyle.CURVE
    val themeProbeIntervalMs: Long? get() = null
    val allowRenderNodeRefresh: Boolean get() = false
    /** Reuse the cached optical blur node instead of rebuilding it during page transitions. */
    val reuseOpticalBlur: Boolean get() = false
    /** Retained live RenderNode dependencies update without rebuilding the blur wrapper. */
    val retainsLiveOpticalBlur: Boolean get() = false
    /** Keep optical input available while native page-navigation work is prioritized. */
    val keepsBackdropDuringTapTransition: Boolean get() = false
    /** Keep capture invalidation active while a live backdrop listener is attached. */
    val captureDuringLiveBackdrop: Boolean get() = true
    /** Native source is retained for clicks only; the glass host owns visible rendering. */
    val hideRedrawnNavigationSource: Boolean get() = false
    /** Optional source projection rules for app-specific centre actions. */
    val sourceCenterActionIndex: Int? get() = null
    val sourceDefaultTranslationDp: Float get() = 0f
    val sourceCenterTranslationDp: Float get() = 0f
    val sourceCenterClipRatio: Float get() = 1f
    val preserveNativeColorIndices: Set<Int> get() = emptySet()
    /** Delay before a scroll-idle page hides the glass bar. */
    fun scrollStopHideDelayMs(selectedIndex: Int): Long = 1_000L
    /** Some native rows need a short settle window after dispatching a click. */
    val selectionSettleMs: Long get() = 0L
    /** App-owned scroll-stop-hide tab; the scroll listener itself is shared. */
    fun scrollStopHideTab(selectedIndex: Int): Boolean = false
    fun resetScrollStopHideOnOtherTabs(): Boolean = false
    fun isBackPeekTab(selectedIndex: Int): Boolean = false
    fun isBackPeekActivity(activity: Activity): Boolean = false
    val backPeekDurationMs: Long get() = 0L
    val sceneVisibilityAnimation: Boolean
        get() = TargetSpecRegistry.byKey[key]?.sceneVisibilityAnimation == true
    val drawsNavigationInOptics: Boolean
        get() = TargetSpecRegistry.byKey[key]?.drawsNavigationInOptics == true
    val suppressSourceOnHostRebind: Boolean get() = false
    val snapIndicatorToSelectionOnRebind: Boolean get() = false
    val refreshHostGeometryOnRebind: Boolean get() = false
    val accentColor: Int? get() = TargetSpecRegistry.byKey[key]?.glassAccentColor
    val reverseVisualSlotOrder: Boolean get() = false
    val actionOnlyIndices: Set<Int> get() = emptySet()
    /** Live native routing may replace the scene while a bar gesture is animating. */
    val updatesSceneDuringTapTransition: Boolean get() = false
    val floatingPillUsesSharedInsets: Boolean get() = false
    val preserveNativeParentBackground: Boolean get() = false
    val preserveNativeSourceMetrics: Boolean get() = false
    /** The adapter owns reversible source suppression and leaves native layout intact. */
    val managesNavigationSourceAppearance: Boolean get() = false
    val foregroundNavigationOverSelection: Boolean get() = false
    val usesNavigationOpticalMix: Boolean get() = false
    val persistentNavigationOpticalMix: Boolean get() = false
    val foregroundUsesRenderedSelectionOutline: Boolean get() = false
    val foregroundRequiresSubmittedIndicatorArtwork: Boolean get() = false
    val usesDetachedDirectSelectionFill: Boolean get() = false
    val defersColdTapUntilMotionSettles: Boolean get() = true
    /** Whether the shared tap inset outline can replace the adapter's existing selection geometry. */
    val supportsInsetTapOutline: Boolean get() = true
    /** Keep the inset base outline under the existing drag transform. */
    /** Keep artwork in the foreground when using the inset tap contour. */
    val insetTapArtworkForegroundOnly: Boolean get() = false
    val foregroundMaskInsetDp: Float get() = 1f
    /** Include the lower glass panel's own specular in the upper selection lens. */
    val samplesPanelHighlightInIndicator: Boolean get() = true
    /** Record animated sampled specular separately from the reusable material scene. */
    val independentSampledIndicatorHighlight: Boolean get() = false
    val pressedIndicatorScale: Float? get() = io.github.offlineglass.rendering.BottomBarIndicatorStyle.PRESS_SCALE
    /** Default-on expansion is unchanged for bottom navigation and ordinary controls. */
    val indicatorInteractionPolicy: io.github.offlineglass.rendering.IndicatorInteractionPolicy
        get() = io.github.offlineglass.rendering.IndicatorInteractionPolicy.EXPANDING
    val concentricIndicatorExpansion: Boolean get() = true
    val concentricIndicatorExpansionGain: Float get() = 1.6f
    val activeIndicatorDispersion: Float get() = 0.40f
    /** Whole-button feedback; null preserves the navigation panel's width-derived growth. */
    val outerPanelPressScale: Float? get() = null
    val sampledPanelHighlightWidthDp: Float get() = io.github.offlineglass.rendering.BottomBarIndicatorStyle.SPECULAR_WIDTH_DP
    val sampledPanelHighlightMinimumAlpha: Float get() = io.github.offlineglass.rendering.BottomBarIndicatorStyle.SPECULAR_MIN_ALPHA
    val requestedFrameRateMax: Float? get() = null
    val continuousFramePump: Boolean get() = false
    val observeWindowTouches: Boolean get() = false
    fun onWindowTouch(host: View, event: android.view.MotionEvent) = Unit
    val opticalIconBandScale: Float get() = 1f
    val secondaryBlurScale: Float get() = 1f
    fun backdropBlurEffect(radius: Float): android.graphics.RenderEffect =
        io.github.offlineglass.rendering.NativeBackdropBlur.effect(radius)
    fun liveBackdropTransform(parent: View, host: View, out: android.graphics.Matrix): Boolean = false
    val skipBackdropStableDelay: Boolean get() = false
    val forceCompositorFrames: Boolean get() = false
    val disablePlatformForceDark: Boolean get() = false
    val liveBackdropFramePump: Boolean get() = false
    val refreshOpticsOnBarShow: Boolean get() = false
    val releaseOutlineClipping: Boolean get() = false
    fun hiddenBarTranslationPx(hostHeight: Int, density: Float): Float? = null
    /** Explicit scene direction; null preserves every existing animation scheme. */
    fun sceneExitTranslationPx(hostHeight: Int, density: Float): Float? = null
    /** YouTube-style translation/alpha only, without spring scale/overshoot. */
    val simpleSceneVisibilityAnimation: Boolean get() = false
    /** Shared low-power Surface/PixelCopy engine; page selection remains app-owned. */
    val usesOpticalSurfacePipeline: Boolean get() = false
    val keepsOpticalSurfaceDuringBarAnimation: Boolean get() = false
    val retainsOpticalSurfaceWhenBarHidden: Boolean get() = false
    val opticalSurfaceFollowsBarTransform: Boolean get() = true
    val usesDirectOpticalBackdropMode: Boolean get() = false
    val usesNativeOpticalBlur: Boolean get() = false
    val ownsOpticalSurfaceLayout: Boolean get() = false
    /** Adapter positions its host; slot/config updates must not apply bottom-bar geometry. */
    val ownsHostLayout: Boolean get() = false
    /** Transparent optical buffer margin, including animated ambient shadows. */
    val opticalSurfaceOverflowDp: Float get() = 32f
    /** Defer sibling removal until the parent finishes its detach traversal. */
    val deferCompanionRemovalOnDetach: Boolean get() = false
    fun opticalSurfaceLayout(
        contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
        hostWidth: Int, hostHeight: Int, overflow: Int,
    ): OpticalSurfaceLayout? = null
    val allowsSystemBackgroundBlur: Boolean get() = true
    val countsOpticalCompositorCopies: Boolean get() = false
    val deferOpticalRenderDuringBarAnimation: Boolean get() = false
    val reuseSurfaceBlurDuringBarAnimation: Boolean get() = false
    val usesContentAlignedOpticalSurface: Boolean get() = false
    val retainsExistingBackdropRenderNode: Boolean get() = false
    fun isOpticalSurfaceTab(selectedIndex: Int): Boolean = false
    val restingOpticalMix: Float? get() = null
    val logNavigationHierarchy: Boolean get() = false
    val clearSourceSelectionSurfaces: Boolean get() = false
    val trackSourceDrawableIdentity: Boolean get() = false
    /** Some native ImageViews draw their icon from background instead of src. */
    val trackSourceImageBackgroundIdentity: Boolean get() = false
    val managesNativeTabVisibility: Boolean get() = false
    val honorsBackdropCaptureSetting: Boolean get() = false
    fun normalizeConfig(config: GlassConfig): GlassConfig = config
    fun hostBottomMarginPx(view: View, defaultGap: Int): Int = defaultGap
    fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): OpticalCaptureGeometry? = null
    fun createNavigationState(context: Context): AppNavigationState? = null
    fun createNavigationState(context: Context, host: io.github.offlineglass.hook.GlassHostLayout): AppNavigationState? =
        createNavigationState(context)
    fun createNativeChromeController(): AppNativeChromeController? = null
    fun screenReferenceHeight(rootHeight: Int?, displayHeight: Int): Int? = null
    fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = Unit
    val hasTransientPageVisibility: Boolean get() = false
    /** Beta build policy: keep the bottom bar on the app home screen only. */
    val homeOnlyNavigation: Boolean get() = false
    fun navigationSurfaceColor(activity: Activity?, source: View?, dark: Boolean): Int? = null
    /** Visible glass fill, also used in the indicator's captured navigation row. */
    /** Opt-in factor after the shared global surface opacity. */
    fun surfaceOpacityScale(config: GlassConfig): Float = 1f
    fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int): Int = defaultColor
    fun glassSurfaceFillBlendMode(dark: Boolean): android.graphics.BlendMode? = null
    fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean = false
    fun configureNavigationInsets(decor: View, enabled: Boolean) = Unit
    fun immersionWasReset(immersiveStub: Any?): Boolean = false
    fun pushedConfig(): GlassConfig? = null
    fun acceptPushedConfig(config: GlassConfig): Boolean = false
    fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? = null
    fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? = null
    fun adjustNavigationScore(view: ViewGroup, idName: String?, score: Int): Int = score
    fun onNavigationCandidates(candidates: List<NavigationCandidate>) = Unit
    fun suppressNativeBottomChrome(navigation: ViewGroup) = Unit
    /** Host-aware variant for adapters that must exclude the glass host subtree from tree walks. */
    fun suppressNativeBottomChrome(host: View, navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)
    fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) = Unit
    fun prepareContentForInstall(content: ViewGroup) = Unit
    fun prepareNavigationSource(navigation: ViewGroup, density: Float) = Unit
    /** Return true when the adapter fully configured this source label. */
    fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean = false
    /** Return true when the adapter fully configured this source icon. */
    fun configureSourceIcon(view: ImageView, config: GlassConfig, density: Float): Boolean = false
    fun resolveNestedNavigation(navigation: ViewGroup): Pair<ViewGroup, Int>? = null
    fun acceptsNavigationSource(navigation: ViewGroup): Boolean = true
    fun hookSignals(): List<TargetHookSignal> = emptyList()
    fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) = Unit
    fun beforeNavigationScan(activity: Activity) = Unit
    fun isBlockingOverlayActivity(activity: Activity): Boolean = false
    fun setBlockingOverlayVisible(visible: Boolean) = Unit
    fun isBlockingOverlayVisible(): Boolean = false
}

internal typealias TargetHookSignal = HookSignal

/** Adapter-owned overlay excluded from generic sampling and traversal. */
internal interface AdapterOwnedOverlay

/** Generic compound panel geometry, supplied exclusively by the app adapter. */
internal data class AdapterBarLayout(
    val mainWidth: Float,
    val actionLeft: Float,
    val actionSize: Float,
    val actionIndex: Int,
    val navigationIndices: List<Int>,
    /** Optional action material, replacing the default light/dark panel fill. */
    val actionFillColor: Int? = null,
    /** Evaluate a detached lens in local coordinates with its own filtered input bounds. */
    val localActionOptics: Boolean = false,
    /** Opt-in: main capsule owns a separate local lens, never a compound SDF. */
    val localMainOptics: Boolean = false,
    /** Strength of the detached action's liquid highlight relative to the panel default. */
    val actionHighlightScale: Float = 1f,
    /** Keep the supplied RGB and use the surface slider, including in solid bar mode. */
    val actionFillUsesOnlySurfaceOpacity: Boolean = false,
)

internal data class AdapterNavigationFrame(
    val context: Context,
    val extraScale: Float,
    val slotCount: Int,
    val width: Int,
    val height: Int,
    val slotWidth: Float,
    val density: Float,
    val iconScale: Float,
    val textSize: Float,
    val scaledDensity: Float,
    val selectedIndex: Int,
    val enabledIndices: List<Int>,
    val iconOnly: Boolean,
    val darkGlass: Boolean,
    val accentColor: Int,
    val barLayout: AdapterBarLayout? = null,
    val navigationOpticalMix: Float = 0f,
    val hideSelectedForOptics: Boolean = false,
    val targetKey: String = "",
    val managerEnglishLabels: Boolean = false,
    val detachedDirectSelection: Boolean = false,
    val topBarTabs: io.github.offlineglass.hook.topbar.TopBarTabLayout? = null,
) {
    fun channelCenter(index: Int): Float =
        topBarTabs?.takeIf { index in 0 until it.size }?.center(index, width, 4f * density)
            ?: 4f * density + (index + .5f) * slotWidth
    fun channelEdge(index: Int): Float =
        topBarTabs?.takeIf { index in 0..it.size }?.edge(index, width, 4f * density)
            ?: 4f * density + index * slotWidth
}

internal fun AdapterNavigationFrame.localizeNavigationLabel(label: String): String =
    if (managerEnglishLabels) NavigationLabelLocalization.toEnglish(targetKey, label) else label

/** A verified scene node, positioned in physical screen coordinates. */
internal data class RetainedNavigationBackdrop(val node: android.graphics.RenderNode, val toScreen: android.graphics.Matrix)

internal data class AppVideoBackdrop(
    val bitmap: android.graphics.Bitmap,
    val surfaceLeft: Int,
    val surfaceTop: Int,
    val surfaceWidth: Int,
    val surfaceHeight: Int,
    val epoch: Int,
)

/** Per-host mutable navigation resources owned by one app, not the shared glass renderer. */
internal data class NavigationCaptureLabels(
    val labels: List<TextView>,
    val retryDelayMs: Long = 0L,
)

internal data class OpticalCaptureGeometry(
    val sourceRect: android.graphics.Rect,
    val targetWidth: Int,
    val targetHeight: Int,
    val barSource: android.graphics.Rect,
)

internal data class OpticalSurfaceLayout(
    val width: Int, val height: Int, val left: Int, val top: Int,
    val right: Int, val bottom: Int, val gravity: Int,
)

internal interface AppNavigationState {
    /** Dynamic capability shared by projection, hit testing and selection. */
    fun isNavigationEntryAvailable(index: Int): Boolean? = null
    /** Optional animated indicator center/width; null keeps normal slot geometry. */
    fun indicatorHorizontalGeometry(center: Float, width: Float): Pair<Float, Float>? = null
    /** Map physical touch coordinates into the host's uniform slot coordinate space. */
    fun uniformSlotTouchX(x: Float, hostWidth: Int, padding: Float): Float = x
    /** Optional detached action feedback; zero preserves existing adapters. */
    fun actionButtonPressProgress(): Float = 0f
    /** Animated panel width; default retains the common layout. */
    fun hostPanelWidth(defaultWidth: Int): Int = defaultWidth
    /** Opt-in measured geometry; keeps optics and input in the panel's real coordinates. */
    fun hostPanelHeight(defaultHeight: Int): Int = defaultHeight
    /** Null uses normal navigation gestures; true/false owns this event's dispatch. */
    fun interceptNavigationTouch(host: View, event: android.view.MotionEvent): Boolean? = null
    /** Some native scenes contain GPU-only assets and cannot be drawn to a Bitmap Canvas. */
    val allowsSoftwareBackdropCapture: Boolean get() = true
    /** A new native page/content revision must not reuse a previous-page software input. */
    fun needsFreshSoftwareBackdrop(): Boolean = false
    fun onSoftwareBackdropRecorded() = Unit
    /** Lightweight adapter-owned work that runs from the shared pre-draw cadence. */
    fun onHostFrame(host: View) = Unit
    /** Default null: persistent first-visit pictures are an adapter-only experiment. */
    fun firstVisitPresentation(host: View): io.github.offlineglass.rendering.FirstVisitPresentation? = null
    fun onContentScroll() = Unit
    fun onNavigationTabSwitched(index: Int, slotCount: Int, now: Long) = Unit
    fun contentMotionHoldUntil(): Long = 0L
    fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean = false
    fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean? = null
    fun followingNativeNavigationAnimation(now: Long): Boolean = false
    fun pendingNativeNavigationTarget(now: Long): Int? = null
    val readyForDrawing: Boolean get() = true
    val blocksBackdropSampling: Boolean get() = false
    fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) = Unit
    fun captureNavigation(source: ViewGroup, target: android.graphics.Bitmap): Boolean = false
    fun acceptCapturedNavigation(source: ViewGroup?, target: android.graphics.Bitmap, selectedIndex: Int): Boolean = true
    fun acceptAndCommitNavigationSnapshot(
        source: ViewGroup?, target: android.graphics.Bitmap, selectedIndex: Int, slotCount: Int,
        commit: (android.graphics.Bitmap) -> Unit,
    ): Boolean? = null
    fun inspectNavigationCaptureLabels(source: ViewGroup, hasSnapshot: Boolean): NavigationCaptureLabels? = null
    fun temporarilyHideNavigationLabels(labels: List<TextView>, selectedIndex: Int): List<Pair<TextView, Int>> = emptyList()
    /** Null uses the shared snapshot cadence; true skips this frame's capture. */
    fun skipNavigationSnapshotCapture(
        source: ViewGroup, snapshot: android.graphics.Bitmap?, touching: Boolean,
        visualAnimating: Boolean, slotCount: Int, now: Long,
    ): Boolean? = null
    fun replaceCapturedVideoSlot(target: android.graphics.Bitmap) = Unit
    fun drawFixedNavigationSlot(canvas: Canvas, index: Int, selectedIndex: Int, dark: Boolean,
                                centerX: Float, centerY: Float, boxSize: Float): Boolean = false
    fun sourceContentOffsetY(index: Int, selectedIndex: Int, hostHeight: Int,
                             transformDy: Float, transformScale: Float): Float? = null
    fun drawAppBadgeOverlay(canvas: Canvas, host: View, contentScale: Float) = Unit
    /** App-owned snapshot crop/repaint stages for native tab artwork. */
    fun transformNavigationSnapshot(source: ViewGroup, snapshot: android.graphics.Bitmap, slotCount: Int): android.graphics.Bitmap? = null
    fun onNavigationSnapshotReady(snapshot: android.graphics.Bitmap, slotCount: Int) = Unit
    fun drawNavigationSnapshotOverlay(canvas: Canvas, frame: AdapterNavigationFrame,
                                      snapshot: android.graphics.Bitmap?) = Unit
    /** Whether the current app mode needs a sampled copy of content behind the bar. */
    fun requiresSampledBackdrop(): Boolean = true
    /** Refract adapter-owned navigation artwork even when the page owns its own compositor. */
    val usesArtworkOnlyIndicatorLens: Boolean get() = false
    fun needsSurfaceBackdrop(): Boolean = false
    fun maintainsCompositorSamplingWithoutSurface(): Boolean = false
    /** Desired renderer mode, independent of asynchronous Surface lifecycle. */
    fun allowsOpticalSurfacePipeline(): Boolean = true
    /** Window-strip capture needs a transparent output surface before its first frame. */
    fun preparesOpticalSurfaceForWindowCapture(): Boolean = false
    /** Reject readbacks requested for a scene that has since been replaced. */
    fun opticalCaptureEpoch(): Long = 0L
    /** Optional app-owned worker for PixelCopy callbacks; null keeps the shared worker. */
    fun opticalSurfaceCopyHandler(): android.os.Handler? = null
    /** Optional adapter-owned ordering of readback relative to app frame submission. */
    fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit): Boolean = false
    fun onOpticalSurfaceCopyCompleted(root: View, nextCopy: () -> Unit) = Unit
    /** Reuse the retired buffer for the next copy while the published frame renders. */
    fun overlapsOpticalCopyAndRender(): Boolean = false
    /** Coalesce new samples with native navigation drawing on the UI frame. */
    fun deferOpticalCopyRenderToUi(): Boolean = false
    fun directOpticalBackdropActive(): Boolean = false
    fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float): Boolean? = null
    fun enterDirectOpticalBackdrop(surface: android.view.SurfaceView, pipelineActive: Boolean): Boolean = false
    fun onOpticalSurfaceFrameInvalidated() = Unit
    fun onOpticalSurfacePipelineEnabled(wasActive: Boolean) = Unit
    fun onOpticalBackdropCaptured(barSource: android.graphics.Rect?) = Unit
    /** A changed sampled image can indicate movement without a View scroll event. */
    fun onOpticalBackdropChanged() = Unit
    fun onOpticalSurfaceFrameRendered(): Boolean = false
    fun onOpticalRendererConfigurationChanged() = Unit
    fun holdDirectBackdropUntilOpticalFrame(liveBackdropActive: Boolean, hasDisplayList: Boolean): Boolean = false
    fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? = null
    fun queueOpticalSurfaceRender(
        handler: android.os.Handler?, active: () -> Boolean, render: () -> Unit,
    ): Boolean = false
    fun syncNativeSurfaceOuterEffect(
        view: View, liquidGlassEnabled: Boolean, width: Int, height: Int, blurRadius: Float,
        cornerRadiusPercent: Float, density: Float, shaderFactory: () -> android.graphics.RuntimeShader?,
    ): Boolean = false
    fun retainBackdropDuringTransition(now: Long): Boolean = false
    fun retainHomeBackdrop(selectedIndex: Int): Boolean = false
    fun deferThemeDetection(now: Long): Boolean = false
    fun needsContinuousHomeFrames(selectedIndex: Int): Boolean = false
    fun pageRequiresScrollStopHide(selectedIndex: Int): Boolean = false
    fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean = false
    fun retainNavigationWithoutNativeSource(selectedIndex: Int): Boolean =
        retainNavigationWhenNativeRowHidden(selectedIndex)
    fun onBarGesture(phase: Int, host: View) = Unit
    fun suppressNativeChrome(source: ViewGroup?) = Unit
    fun suppressNativeChromeEarly(source: ViewGroup?) = Unit
    fun suppressHostNativeChrome(source: ViewGroup?, root: View, selected: Int) = Unit
    fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float): Boolean = false
    /** Distinguish lens input artwork from the ordinary foreground projection. */
    fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float, forOptics: Boolean): Boolean =
        drawNativeNavigation(canvas, source, density)
    fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) = Unit
    /** Return true when an app configured its own compositor/backdrop transition policy. */
    fun updateBackdropRendererMode(host: io.github.offlineglass.hook.GlassHostLayout): Boolean = false
    fun onHostFocusLost() = Unit
    fun onHostWindowFocusChanged(hasFocus: Boolean) = Unit
    fun onHostDetached() = Unit
    fun onHostPreDrawDuringTapTransition(host: View) = Unit
    fun syncVisibilityCompanion(host: View, selectedIndex: Int): Boolean = false
    fun beforeHostDraw(host: View, source: ViewGroup?): Boolean = true
    fun hostChromeAlpha(host: View, selectedIndex: Int): Float = 1f
    fun drawAdditionalAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float) = Unit
    fun drawsPanelAmbientShadow(): Boolean = true
    fun drawPanelEdgeGlow(canvas: Canvas, path: android.graphics.Path, density: Float) = Unit
    fun drawSampledBackdrop(canvas: Canvas, bitmap: android.graphics.Bitmap,
                            destination: android.graphics.RectF, paint: android.graphics.Paint): Boolean = false
    fun reuseOuterBackdropForSurface(surfaceActive: Boolean): Boolean = false
    fun outerBackdropNeedsRecord(
        backdrop: android.graphics.Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean? = null
    fun onOuterBackdropRecorded(backdrop: android.graphics.Bitmap?, width: Int, height: Int) = Unit
    fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = false
    /** Reuse captured indicator inputs while animating only their release opacity. */
    val retainsIndicatorReturnLayers: Boolean get() = false
    fun combinedIndicatorSceneNeedsRecord(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean,
    ): Boolean? = null
    fun onCombinedIndicatorSceneRecorded(
        backdrop: android.graphics.Bitmap?, navigation: android.graphics.Bitmap?, signature: Long,
        width: Int, height: Int, dark: Boolean,
    ) = Unit
    fun drawHiddenTabsInIndicatorScene(hybridBackdrop: Boolean): Boolean? = null
    /** Compose the lens from this frame's rendered panel, retaining navigation artwork. */
    fun indicatorUsesPanelBackdrop(): Boolean = true
    /** Retain resting material independently of backdrop transitions; gestures stay clear. */
    val persistentRestingIndicatorFill: Boolean get() = true
    /** Keep material only inside the hidden optical row during indicator gestures. */
    val retainHiddenSourceFillWhileExpanded: Boolean get() = true
    fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean? = null
    fun opticalBlurNodeNeedsRecord(
        prepared: Boolean, backdrop: android.graphics.Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?,
    ): Boolean? = null
    fun onOpticalBlurNodeRecorded(
        backdrop: android.graphics.Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?,
    ) = Unit
    fun skipLiveBackdrop(selectedIndex: Int): Boolean = false
    fun canReuseLiveBackdrop(selectedIndex: Int): Boolean = false
    /** Refresh retained GPU wrappers when a texture producer supplies a new frame. */
    fun forceLiveBackdropFrame(selectedIndex: Int): Boolean = false
    fun onLiveBackdropRecorded(selectedIndex: Int) = Unit
    fun beginOverlayTouch(host: View, event: android.view.MotionEvent): Boolean = false
    fun hasActiveOverlayTouch(): Boolean = false
    fun forwardOverlayTouch(host: View, event: android.view.MotionEvent) = Unit
    fun clearOverlayTouch() = Unit
    fun navigationBarGeometry(): android.graphics.RectF? = null
    fun allowsHostTouch(): Boolean = true
    fun setNativeBarTouchable(view: View?, touchable: Boolean): Boolean = false
    fun onHostTouchDown(host: View, selectedIndex: Int) = Unit
    /** Keep non-panel scene work out of an app-owned exit animation when required. */
    val pauseBackdropDuringBarExit: Boolean get() = false
    /** Keep page/lifecycle tracking active while an invisible panel needs no sampling. */
    fun pauseBackdropWork(host: View): Boolean = false
    /** The focused native window of a control whose material is drawn in its parent window. */
    fun focusedOpticalWindow(host: View): View? = null
    /** Pause only optical readback/probe wakeups while retaining a hidden Surface. */
    fun pauseOpticalSurfaceProbe(): Boolean = false
    fun drawsNavigationContent(): Boolean = true
    /** Per-host opt-out for optional artwork modes; default preserves adapters. */

    fun navigationOpticalMixEnabled(): Boolean = true

    /** Page-owned tint animation, independent of artwork and optical strength. */
    fun surfaceTintFraction(): Float = 1f
    fun drawsSelectionFillOnly(): Boolean = false
    fun selectionOpticalFraction(): Float = 1f
    /** Restrict sampled artwork to the same footprint excluded from foreground artwork. */
    fun clipsSampledNavigationToIndicator(): Boolean = false
    fun suppressRedrawnSource(): Boolean? = null
    fun forceDarkMode(selectedIndex: Int): Boolean? = null
    fun resolveAutoDarkMode(host: View): Boolean? = null
    fun resolveBackdropScene(host: View): View? = null
    val suppliesRetainedNavigationBackdrop: Boolean get() = false
    fun retainedNavigationBackdrop(): RetainedNavigationBackdrop? = null
    /** Opt in to a filtered descendant list inside resolveBackdropScene's root. */
    val backdropSceneViewsAreDescendants: Boolean get() = false
    /** Optional safe subtrees; ancestor nodes containing media surfaces must not be replayed. */
    fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View>? = null
    fun enforceContentClipping() = Unit
    fun refreshNavigationSource(host: View): Boolean = false
    fun backdropSceneBaseColor(scene: View, fallback: Int): Int = android.graphics.Color.TRANSPARENT
    fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? = null
    fun adjustFloatingActions(host: View, source: ViewGroup?) = Unit
    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = Unit
    fun logDiagnostic(message: String) = Unit
    fun requestVideoBackdrop(host: View, pageAllowed: Boolean) = Unit
    fun videoBackdrop(): AppVideoBackdrop? = null
    fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame, selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean = false
    /** Optional app-owned edge composition; false retains the shared path clip. */
    fun drawSelectionBackdrop(canvas: Canvas, path: android.graphics.Path, selectedIndex: Int,
                              drawContent: () -> Unit): Boolean = false
    fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean = false
    fun dispatchNativeNavigationTap(
        host: io.github.offlineglass.hook.GlassHostLayout, source: ViewGroup?, index: Int, slotCount: Int,
    ): Boolean = false
    fun onTapSucceeded(requestRefresh: (Long) -> Unit) = Unit
    fun pageAllowsNavigation(root: View?): Boolean? = null
    fun hostPageAllowsNavigation(host: io.github.offlineglass.hook.GlassHostLayout, root: View?): Boolean? =
        pageAllowsNavigation(root)
    /** Opt in when a native destination must cover the bar in its first frame. */
    fun skipNavigationExitAnimation(root: View?): Boolean = false
    /** Null leaves system navigation-bar immersion to the common page policy. */
    fun forceNavigationBarImmersed(host: io.github.offlineglass.hook.GlassHostLayout): Boolean? = null
    fun handleSystemBack(host: View): Boolean = false
    fun transientPageAllowsNavigation(activity: Activity?): Boolean = true
    fun dispose() = Unit
}


/** Mutable, app-owned source-chrome cleanup invoked at the host's existing cadence. */
internal interface AppNativeChromeController {
    fun update(host: View, source: ViewGroup?)
}

/** Explicit registry: an app adapter cannot accidentally run in another package. */
internal object TargetAdapterRegistry {
    private val custom = listOf(
        QqMusicAdapter,
        SpotifyAdapter,
        AppleMusicAdapter,
        DouyinAdapter,
        io.github.offlineglass.hook.adapters.tiktok.TikTokAdapter,
        NeteaseAdapter,
        PddAdapter,
        AmapAdapter,
        HelloBikeAdapter,
        MiCommunityAdapter,
        XhsAdapter,
        BilibiliAdapter,
        BilibiliInAdapter,
        MiHealthAdapter,
        MiHomeAdapter,
        MeituanTakeoutAdapter,
        MeituanMainAdapter,
        FileManagerAdapter,
        TiebaAdapter,
        WeChatAdapter,
        WeiboAdapter,
        MiMarketAdapter,
        YouTubeAdapter,
        TaobaoAdapter,
        JdAdapter,
        XianyuAdapter,
        CainiaoAdapter,
        XjtuAdapter,
        io.github.offlineglass.hook.adapters.zhihu.ZhihuAdapter,
        ChatGptAdapter,
        io.github.offlineglass.hook.adapters.huawei_health.HuaweiHealthAdapter,
        XAdapter,
        InstagramAdapter,
        io.github.offlineglass.hook.adapters.reddit.RedditAdapter,
        io.github.offlineglass.hook.adapters.quora.QuoraAdapter,
    ).associateBy(TargetAdapter::key)

    private val byKey: Map<String, TargetAdapter> = TargetSpecRegistry.all.associate { spec ->
        spec.key to (custom[spec.key] ?: MetadataOnlyAdapter(spec.key))
    }
    private val byPackage: Map<String, TargetAdapter> = TargetSpecRegistry.all.flatMap { spec ->
        (listOf(spec.packageName) + spec.aliasPackages).map { pkg -> pkg to byKey.getValue(spec.key) }
    }.toMap()

    fun forSpec(spec: TargetSpec): TargetAdapter? = byKey[spec.key]

    fun forKey(key: String): TargetAdapter? = byKey[key]

    fun forPackage(packageName: String): TargetAdapter? = byPackage[packageName]

    private class MetadataOnlyAdapter(override val key: String) : TargetAdapter
}

internal object AdapterNavigationSearch {
    fun findFirst(root: View, predicate: (ViewGroup) -> Boolean): ViewGroup? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 4_000) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && depth <= 14 && predicate(view)) return view
            if (view is ViewGroup && depth < 16) {
                for (index in view.childCount - 1 downTo 0) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        return null
    }

    fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()
}



