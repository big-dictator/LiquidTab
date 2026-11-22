package io.github.offlineglass.hook.adapters.spotify

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.targets.TargetSpec

/** Own the real Compose container, not its virtual accessibility tab nodes. */
internal object SpotifyAdapter : TargetAdapter {
    // Home/Search/Library are pages; reserved/Create slots are native actions.
    override fun isFirstVisitPage(source: ViewGroup, index: Int, slots: Int) = index in 0..2
    @Volatile private var latestPushedConfig: io.github.offlineglass.config.GlassConfig? = null
    override fun pushedConfig() = latestPushedConfig
    override fun normalizeConfig(config: io.github.offlineglass.config.GlassConfig): io.github.offlineglass.config.GlassConfig {
        val visibleMask = SpotifyNativeTabs.indices().fold(0) { mask, index ->
            if (index == 3 || (index == 4 && !SpotifyNativeTabs.createVisible)) mask else mask or (1 shl index)
        }
        return config.copy(hiddenMask = (config.hiddenMask and 31.inv()) or (31 xor visibleMask),
            spotifyPremiumAvailable = SpotifyNativeTabs.premiumAvailable, spotifyCreateCommand = false,
            spotifyThreeTabFullWidth = true, classicNavigation = false)
    }
    override fun acceptPushedConfig(config: io.github.offlineglass.config.GlassConfig): Boolean {
        latestPushedConfig = normalizeConfig(config).normalized()
        return true
    }
    fun reconcileNativeTabs(mask: Int, hidden: Int, premium: Boolean) {
        latestPushedConfig?.let {
            latestPushedConfig = it.copy(hiddenMask = (it.hiddenMask and mask.inv()) or (hidden and mask),
                spotifyPremiumAvailable = premium,
                spotifyCreateCommand = it.spotifyCreateCommand && mask and 16 == 0)
        }
    }
    override val key = "spotify"
    override val supportsNativeEnglishLocale = true
    override val flowingLiquidEnabled = true
    override val liquidMotionEnabled = true
    override val reuseOpticalBlur = true
    private var detachedLayoutWidth = 0
    private var detachedLayoutHeight = 0
    private var detachedLayoutDensity = 0f
    private var detachedLayoutMask = -1
    private var detachedLayout: io.github.offlineglass.hook.adapters.AdapterBarLayout? = null
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int,
        height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || !SpotifyNativeTabs.createVisible ||
            config.hiddenMask and 16 != 0 || height <= 0 || width <= height + 12f * density) return null
        val mask = (config.hiddenMask and 15) or (if (SpotifyNativeTabs.premiumAvailable) 0 else 8)
        if (mask == 15) return null
        if (width != detachedLayoutWidth || height != detachedLayoutHeight ||
            density != detachedLayoutDensity || mask != detachedLayoutMask || detachedLayout == null) {
            val navigation = (0..3).filter { mask and (1 shl it) == 0 }
            val actionLeft = width - height.toFloat()
            detachedLayout = io.github.offlineglass.hook.adapters.AdapterBarLayout(
                mainWidth = (actionLeft - 12f * density).coerceAtLeast(1f),
                actionLeft = actionLeft, actionSize = height.toFloat(), actionIndex = 4,
                navigationIndices = navigation,
            )
            detachedLayoutWidth = width; detachedLayoutHeight = height
            detachedLayoutDensity = density; detachedLayoutMask = mask
        }
        return detachedLayout
    }
    override fun hostWidthSlotCount(slots: Int, config: io.github.offlineglass.config.GlassConfig): Int =
        if (slots == 3) 5 else slots
    private val backdropTargetMatrix = android.graphics.Matrix()
    override val skipBackdropStableDelay = true
    override fun backdropBlurEffect(radius: Float) = SpotifyBackdropSampling.effect(radius)
    override fun liveBackdropTransform(parent: View, host: View, out: android.graphics.Matrix) =
        SpotifyBackdropSampling.transform(parent, host, out, backdropTargetMatrix)
    override val ownsNavigationFinding = true
    override val recheckNavigationBindingOnLayout = true
    override val preserveNativeSourceMetrics = true
    override val navigationSignatureMaxDepth = 14
    override val navigationSnapshotStableFrames = 2
    override val nativeSelectionReliable = true
    override val hasPerFrameScene = true
    override val sceneVisibilityAnimation = true
    override val suppressPlatformElevation = true
    override val ownsNavigationDrawing = true
    override val foregroundNavigationOverSelection = true
    override val drawsNavigationInOptics = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override val foregroundMaskInsetDp = 0f
    override val skipNavigationSnapshot = true
    override fun surfaceOpacityScale(config: io.github.offlineglass.config.GlassConfig) = config.spotifyBarOpacity
    override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int) =
        defaultColor

    override fun createNavigationState(context: android.content.Context): AppNavigationState =
        SpotifyNavigationState()

    override fun beforeNavigationScan(activity: android.app.Activity) {
        SpotifyChromeOwnership.attach(activity)
        SpotifyTopContinuity.prepare(activity)
        SpotifyHomeTopBar.attach(activity)
        SpotifySearchTopBar.attach(activity)
        SpotifyLibraryTopBar.attach(activity)
    }

    override fun installBootstrapHooks(param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        val loader = param.classLoader
        val packageName = param.packageName
        io.github.offlineglass.hook.compat.XposedBridge.hookAllMethods(android.app.Application::class.java, "attach",
            object : io.github.offlineglass.hook.compat.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args.firstOrNull() as? android.content.Context ?: return
                    if (context.packageName != packageName) return
                    SpotifyNativeTabs.install(loader, context.applicationInfo.sourceDir, context.applicationInfo.dataDir)
                    // Bind pre-draw observers before the first window frame,
                    // rather than waiting for the bottom-navigation scan.
                    (param.thisObject as? android.app.Application)?.registerActivityLifecycleCallbacks(
                        object: android.app.Application.ActivityLifecycleCallbacks {
                            override fun onActivityCreated(activity: android.app.Activity,state: android.os.Bundle?) {
                                if(activity.packageName==packageName)beforeNavigationScan(activity)
                            }
                            override fun onActivityStarted(activity: android.app.Activity) {
                                // Retry attach after activities finish installing their content.
                                if(activity.packageName==packageName)beforeNavigationScan(activity)
                            }
                            override fun onActivityResumed(activity: android.app.Activity) { SpotifyProfileArtwork.retry() }
                            override fun onActivityPaused(activity: android.app.Activity)=Unit
                            override fun onActivityStopped(activity: android.app.Activity)=Unit
                            override fun onActivitySaveInstanceState(activity: android.app.Activity,state: android.os.Bundle)=Unit
                            override fun onActivityDestroyed(activity: android.app.Activity) {
                                SpotifyHomeTopBar.release(activity);SpotifySearchTopBar.release(activity)
                                SpotifyLibraryTopBar.release(activity);SpotifyTopContinuity.release(activity)
                                SpotifyChromeOwnership.release(activity)
                                SpotifyBackdropScene.release(activity.findViewById(android.R.id.content))
                            }
                        })
                }
            })
    }

    override fun installHooks(lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        SpotifyIndicatorHooks.install(lpparam.classLoader)
        SpotifyPlaybackHooks.install(lpparam.classLoader)
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        // A new/unknown native model must not be interpreted as five slots.
        if (!SpotifyNativeTabs.layoutKnown) return null
        if (root.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            val host = AdapterNavigationSearch.findFirst(root) { it is io.github.offlineglass.hook.GlassHostLayout }
            host?.post { (host.parent as? ViewGroup)?.removeView(host) }
            return null
        }
        val navigation = AdapterNavigationSearch.findFirst(root) { group ->
            AdapterNavigationSearch.resourceEntryName(group) == "navigation_bar" &&
                group.javaClass.name == "com.spotify.mainlayout.ui.view.containers.MainLayoutContentContainerView" &&
                group.isAttachedToWindow && group.isShown && group.width > 0 && group.height > 0
        } ?: return null
        return NavigationCandidate(navigation, 5, 1000)
    }

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int = 5

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        navigation.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE

    override fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (index == 3) return false
        if (index == 4 && !SpotifyNativeTabs.createVisible) return false
        source ?: return false
        if (!source.isAttachedToWindow || source.width <= 0 || source.height <= 0 || index !in 0 until slotCount) return false
        val now = SystemClock.uptimeMillis()
        val rail = source.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // horizontal_main renders Home/Search/Library as three vertical rows.
        val nativeEntries = SpotifyNativeTabs.indices()
        val nativeIndex = nativeEntries.indexOf(index)
        if (nativeIndex < 0) return false
        val x = if (rail && index < 3) source.width * .5f else source.width * (nativeIndex + .5f) / nativeEntries.size
        val y = if (rail && index < 3) source.height * (index + .5f) / 3f else source.height * .5f
        SpotifyTopContinuity.begin(source,index)
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 16, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val accepted = source.dispatchTouchEvent(down)
            source.dispatchTouchEvent(up)
            accepted
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
