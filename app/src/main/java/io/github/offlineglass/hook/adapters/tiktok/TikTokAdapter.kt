package io.github.offlineglass.hook.adapters.tiktok

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AppNativeChromeController
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Native five-cell rows verified in 47.0.3 and 47.1.4. */
internal object TikTokAdapter : TargetAdapter {
    override val key = "tiktok"
    override val supportsNativeEnglishLocale = true
    // As in Bilibili, the renderer owns each panel's shadow. The platform
    // outline covers the full host, including the detached action and gap.
    override val suppressPlatformElevation = true
    override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int): Int =
        defaultColor
    override fun surfaceOpacityScale(config: io.github.offlineglass.config.GlassConfig) = config.shortVideoBarOpacity
    private var detachedLayout: io.github.offlineglass.hook.adapters.AdapterBarLayout? = null
    private var detachedWidth = 0
    private var detachedHeight = 0
    private var detachedDensity = 0f
    private var detachedMask = -1
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int,
        height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != 5 || config.hiddenMask and 4 != 0 ||
            height <= 0 || width <= height + 12f * density) return null
        if (width == detachedWidth && height == detachedHeight && density == detachedDensity &&
            config.hiddenMask == detachedMask) return detachedLayout
        val navigation = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = width - height.toFloat()
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = actionLeft - 12f * density, actionLeft = actionLeft,
            actionSize = height.toFloat(), actionIndex = 2, navigationIndices = navigation,
            actionFillColor = null,
            localActionOptics = true,
        ).also {
            detachedLayout = it; detachedWidth = width; detachedHeight = height
            detachedDensity = density; detachedMask = config.hiddenMask
        }
    }
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val opticalIconBandScale = 1.5f
    override val secondaryBlurScale = .72f
    override val restingOpticalMix = .86f
    override val skipBackdropStableDelay = true
    override val forceCompositorFrames = false
    override val continuousFramePump = false
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val requestedFrameRateMax = 120f
    override val hasPerFrameScene = true
    override val nativeSelectionReliable = true
    override val preserveNativeSourceMetrics = true
    override val hideRedrawnNavigationSource = true
    override val navigationImmersionOverride = true
    override val allowRenderNodeRefresh = true
    override val allowsSystemBackgroundBlur = false
    override val usesOpticalSurfacePipeline = true
    // Native photo views and sampled live layers both render through the host.
    // A missing TextureView is not a root-compositor capture configuration.
    override val usesDirectOpticalBackdropMode = false
    override val keepNavigationOverlayOnTop = true
    override val recheckNavigationBindingOnLayout = true
    override val usesContentAlignedOpticalSurface = true
    override val ownsOpticalSurfaceLayout = true
    override fun opticalSurfaceLayout(
        contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
        hostWidth: Int, hostHeight: Int, overflow: Int,
    ): io.github.offlineglass.hook.adapters.OpticalSurfaceLayout? =
        if (hostWidth <= 0 || hostHeight <= 0) null else
            io.github.offlineglass.hook.adapters.OpticalSurfaceLayout(
                hostWidth + overflow * 2, hostHeight + overflow * 2,
                (hostLeft - overflow).coerceAtLeast(0), (hostTop - overflow).coerceAtLeast(0),
                0, 0, android.view.Gravity.TOP or android.view.Gravity.START)
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val opticalSurfaceFollowsBarTransform = false
    override fun immersionWasReset(immersiveStub: Any?): Boolean = immersiveStub != null &&
        runCatching { io.github.offlineglass.hook.compat.XposedHelpers.callMethod(immersiveStub, "isForceImmersive") as? Boolean }
            .getOrNull() == false
    override fun configureNavigationInsets(decor: View, enabled: Boolean) = TikTokInsets.configure(decor, enabled)
    override fun installBootstrapHooks(param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        if (param.processName == param.packageName) TikTokNativeTextureFactory.bootstrap(param.classLoader)
    }
    override fun installHooks(lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        if (lpparam.processName != lpparam.packageName) return
        // An optional native hook must never abort navigation installation.
        listOf<Pair<String, () -> Unit>>(
            "insets" to { TikTokInsets.install() },
            "top bar" to { TikTokTopBar.install(lpparam.classLoader) },
            "comments" to { TikTokCommentVisibility.install(lpparam.classLoader) },
            "assistant page" to { TikTokAssistantVisibility.install(lpparam.classLoader) },
            "player" to { TikTokPlayerLayout.install(lpparam.classLoader) },
            "photos" to { TikTokPhotoLayout.install(lpparam.classLoader) },
            "feed assistant" to { TikTokPageLayout.observeAssistantInflation() },
        ).forEach { (name, install) ->
            runCatching(install).onFailure { android.util.Log.w("TikTokGlassDiag", "$name unavailable; retaining navigation", it) }
        }
    }
    override fun createNavigationState(context: android.content.Context, host: io.github.offlineglass.hook.GlassHostLayout) = TikTokNavigationState(host)
    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? =
        TikTokNativeNavigation.find(root)?.let { NavigationCandidate(it, 5, 1000) }

    override fun createNativeChromeController() = object : AppNativeChromeController {
        override fun update(host: View, source: ViewGroup?) {
            source?.let(::suppressNativeBottomChrome)
        }
    }
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        navigation.background = null
        val shell = navigation.parent as? ViewGroup ?: return
        val tolerance = (2f * navigation.resources.displayMetrics.density).coerceAtLeast(1f)
        for (index in 0 until shell.childCount) {
            val decoration = shell.getChildAt(index)
            // Only a plain, noninteractive hairline adjoining the verified tab
            // row. Do not clear page dividers or a parent containing controls.
            if (decoration.javaClass != View::class.java || decoration.isClickable || decoration.isFocusable ||
                decoration.height <= 0 || decoration.height > tolerance ||
                decoration.width < navigation.width * .95f ||
                kotlin.math.abs(decoration.bottom - navigation.top) > tolerance) continue
            // Clear artwork rather than alpha: feed-only layout restoration
            // restores this line's alpha on inbox/profile and must not revive it.
            if (decoration.background != null) decoration.background = null
        }
    }
}
