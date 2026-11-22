package io.github.offlineglass.hook.adapters.youtube

import io.github.offlineglass.hook.compat.XposedBridge
import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter

/** YouTube-specific navigation diagnostics, outside the generic finder. */
internal object YouTubeAdapter : TargetAdapter {
    override val key = "youtube"
    override val deferCompanionRemovalOnDetach = true
    override val ownsNavigationFinding = true
    override val recheckNavigationBindingOnLayout = true
    override val ownsNavigationTap = true
    private var pivotDiagnosticWritten = false

    override fun findNavigation(root: android.view.View, spec: io.github.offlineglass.targets.TargetSpec): NavigationCandidate? {
        val id = root.resources.getIdentifier("pivot_bar", "id", spec.packageName)
        val source = if (id != 0) root.findViewById<android.view.ViewGroup>(id) else null
        if (source == null || !source.isShown || source.width <= 0 || source.height <= 0) return null
        val binding = YouTubePivotBinding.resolve(source)
        if (io.github.offlineglass.BuildConfig.DEBUG && !pivotDiagnosticWritten && binding != null) {
            pivotDiagnosticWritten = true
            val roles = (0..4).filter { binding?.entry(it) != null }
            val rows = (0 until source.childCount).mapNotNull { source.getChildAt(it) as? android.view.ViewGroup }
                .take(4).map { row ->
                    (0 until row.childCount.coerceAtMost(7)).map { i ->
                        row.getChildAt(i).let { "${it.contentDescription} listener=${it.hasOnClickListeners()}" }
                    }
                }
            source.post {
                runCatching {
                    java.io.File(source.context.getExternalFilesDir(null), "youtube_pivot_binding.txt")
                        .writeText("semantic-pivot roles=$roles rows=$rows")
                }
            }
        }
        if (binding == null) {
            android.util.Log.w("YouTubeGlassDiag", "Pivot row roles or native click targets could not be verified")
            return null
        }
        // Host indices remain the settings indices even if the native row
        // omits a role. Availability and dispatch use the verified cell map.
        return NavigationCandidate(source, 5, 100)
    }
    override val supportsNativeEnglishLocale = true
    override fun onConfigurationReady(context: Context, param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        YouTubeSpeedHaptics.install(context, param.classLoader)
    }
    override val accentColor = 0xFFFF0033.toInt()
    override val usesContentDarkMode = true
    override val hasPerFrameScene = true
    // Page taps must not hold the previous page's pixels for the shared
    // navigation-priority window. Existing single-flight sampling still applies.
    override val keepsBackdropDuringTapTransition = true
    override val nativeNavigationDrawsInSourceSpace = true
    override val skipNavigationSnapshot = true
    override val ownsNavigationDrawing = true
    override val drawsNavigationInOptics = true
    // Split the visible row at the moving lens boundary, then use the same
    // selected/unselected artwork inside the lens source before refraction.
    override val usesNavigationOpticalMix = true
    override val foregroundNavigationOverSelection = true
    override val foregroundUsesRenderedSelectionOutline = true
    override val foregroundRequiresSubmittedIndicatorArtwork = true
    override val suppressPlatformElevation = true
    override val usesOpticalSurfacePipeline = true
    override val usesDirectOpticalBackdropMode = true
    override val usesContentAlignedOpticalSurface = true
    override val ownsOpticalSurfaceLayout = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val opticalSurfaceFollowsBarTransform = false
    override val allowsSystemBackgroundBlur = false
    // Use the shared NativeBackdropBlur -> refraction chain directly. Do not
    // insert a YouTube-owned preblur node or retain its effect across settings.
    // A changing backdrop callback and the UI animation pump otherwise
    // submit the same optical Surface twice during one animation frame.
    override val deferOpticalRenderDuringBarAnimation = true

    override fun barLayout(
        config: GlassConfig, width: Int, height: Int, density: Float, slots: Int,
    ): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || !config.isYouTubeCreateVisible() || slots != 5 ||
            width <= height || height <= 0
        ) return null
        val gap = 12f * density
        val actionLeft = width - height.toFloat()
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = (actionLeft - gap).coerceAtLeast(height.toFloat()),
            actionLeft = actionLeft,
            actionSize = height.toFloat(),
            actionIndex = 2,
            navigationIndices = listOf(0, 1, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 },
            actionFillColor = accentColor,
            localActionOptics = true,
            localMainOptics = true,
        )
    }

    override fun opticalSurfaceLayout(
        contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
        hostWidth: Int, hostHeight: Int, overflow: Int,
    ): io.github.offlineglass.hook.adapters.OpticalSurfaceLayout? {
        if (hostWidth <= 0 || hostHeight <= 0) return null
        return io.github.offlineglass.hook.adapters.OpticalSurfaceLayout(
            hostWidth + overflow * 2, hostHeight + overflow * 2,
            (hostLeft - overflow).coerceAtLeast(0), (hostTop - overflow).coerceAtLeast(0),
            0, 0, android.view.Gravity.TOP or android.view.Gravity.START)
    }

    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): io.github.offlineglass.hook.adapters.OpticalCaptureGeometry? {
        val rect = android.graphics.Rect((left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0), (left + width + padding).coerceAtMost(rootWidth),
            (top + height + padding).coerceAtMost(rootHeight))
        if (rect.isEmpty) return null
        // Preserve the source pixels. Downsampling and stretching introduces
        // softness even at a configured radius of zero, before the shared blur.
        val w = rect.width()
        val h = rect.height()
        return io.github.offlineglass.hook.adapters.OpticalCaptureGeometry(rect, w, h,
            android.graphics.Rect(0, 0, w, h))
    }

    override fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean {
        // Keep the native layout at its baseline. The renderer scales each
        // leaf on the destination Canvas, outside thumbnail_layout's clip.
        view.textSize = config.textSize
        view.scaleX = 1f
        view.scaleY = 1f
        return true
    }

    override fun configureSourceIcon(view: ImageView, config: GlassConfig, density: Float): Boolean {
        view.scaleX = 1f
        view.scaleY = 1f
        return true
    }

    override fun createNavigationState(context: Context, host: GlassHostLayout): YouTubeNavigationState =
        YouTubeNavigationState(host)

    override fun onNavigationCandidates(candidates: List<NavigationCandidate>) {
        XposedBridge.log(
            "[OfflineGlass][YouTubeDiag] find candidates=${candidates.joinToString { candidate ->
                "${candidate.view.javaClass.simpleName}/" +
                    "${AdapterNavigationSearch.resourceEntryName(candidate.view) ?: "no-id"}/" +
                    "slots=${candidate.slotCount}/score=${candidate.score}/" +
                    "${candidate.view.width}x${candidate.view.height}"
            }}",
        )
    }
}
