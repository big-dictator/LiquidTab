package io.github.offlineglass.hook.adapters.jd

import android.content.Context
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.targets.TargetSpec

/** JD-specific policy flags live here; shared rendering remains package-agnostic. */
internal object JdAdapter : TargetAdapter {
    override val flowingLiquidEnabled = false
    override val key = "jd"
    override fun onConfigurationReady(context: android.content.Context,
        param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        if (param.processName == param.packageName) {
            JdNavigationClickGate.prepare(context, param.classLoader)
            JdHomeColdBatch.configure(context)
        }
    }
    // Keep native slot identities: hiding Browse must not renumber cart/messages.
    // No native click or preview capture is requested for this disabled entry.
    override fun isNavigationEntryDisabled(index: Int, slots: Int): Boolean =
        slots == JdNavigationBinding.SLOT_COUNT && index == JdNavigationBinding.BROWSE
    override val ownsNavigationFinding = true
    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? =
        JdNavigationBinding.find(root)?.let { NavigationCandidate(it.view, it.slots.size, 100) }
    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        JdNavigationBinding.resolve(group)?.slots?.size
    override val honorsBackdropCaptureSetting = true
    override val ownsNavigationDrawing = true
    // The lens samples page content only, on both the host and Surface paths.
    override val foregroundNavigationOverSelection = true
    override val stagedNavigationSnapshot = true
    override val ownsNavigationSnapshotCapturePolicy = true
    override val supportsScheduledNavigationSnapshotRefresh = true
    override val navigationSnapshotStableFrames = 1
    override val navigationSignatureMaxDepth = 12
    override val usesCommittedSelection = false
    override val nativeSelectionReliable = true
    // JD rebuilds NavigationGroup while committing a destination page. Keep
    // the already-rendered host alive across that bounded detach/rebind gap.
    override val sourceSwapGraceMs = 1_200L
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = false
    override val usesContentDarkMode = true
    override val ownsOpticalSurfaceLayout = true
    override val redrawSurfaceAfterNavigationCapture = true
    override val sourceContentOffsetDp = -6f
    override fun createNavigationState(
        context: Context, host: io.github.offlineglass.hook.GlassHostLayout,
    ): AppNavigationState = JdNavigationState(host)
    override fun installHooks(
        lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) {
        JdScrollSignals.install(lpparam.classLoader)
        JdStartupAds.install(lpparam.classLoader)
        JdClassicLayout.install(lpparam.classLoader)
        JdNativeContentBinding.install(lpparam.classLoader)
        JdHiddenNavigationBlur.install(lpparam.classLoader)
        JdNavigationBootstrap.install(lpparam.classLoader, spec)
        JdNativePageTransitions.install(lpparam.classLoader)
        JdFragmentProbeReuse.install(lpparam.classLoader)
        // Message staging was measured on-device: only ~12ms moved while
        // adding ~358ms content wait. Keep that pilot/trace hooks inactive.
        JdMessageClassLookup.install(lpparam.classLoader)
        JdHomeColdBatch.install(lpparam.classLoader)
        // The earlier global stack-deferral experiment regressed on-device.
        // Keep JdClassProbeStacks inactive: no Throwable/bootstrap interception.
        // Message lookup above is separate, scoped to its verified own DEX.
        JdHiddenPageWork.install(lpparam.classLoader)
    }

    override val reuseOpticalBlur = true
    override val retainsLiveOpticalBlur = true
    override val keepsBackdropDuringTapTransition = true
    override val allowsSystemBackgroundBlur = false
    override val deferOpticalRenderDuringBarAnimation = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val usesContentAlignedOpticalSurface = true
    override val retainsExistingBackdropRenderNode = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val retainsOpticalSurfaceWhenBarHidden = true
    override val opticalSurfaceFollowsBarTransform = false
    override val usesDirectOpticalBackdropMode = true
    // JD uses live GPU nodes for verified native pages and compositor pixels
    // for independently composed pages. Native window
    // blur is disabled here: even its invisible helper Views cause layout and
    // RenderEffect invalidations when configured on each host pre-draw.
    override val usesNativeOpticalBlur = false
    override val repeatImmersionRestoreWhileInactive = true
    override fun opticalSurfaceLayout(
        contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
        hostWidth: Int, hostHeight: Int, overflow: Int,
    ): io.github.offlineglass.hook.adapters.OpticalSurfaceLayout? {
        if (contentWidth <= 0 || contentHeight <= 0) return null
        val gravity = Gravity.TOP or Gravity.START
        return io.github.offlineglass.hook.adapters.OpticalSurfaceLayout(
            hostWidth + overflow * 2, hostHeight + overflow * 2,
            (hostLeft - overflow).coerceAtLeast(0), (hostTop - overflow).coerceAtLeast(0),
            0, 0, gravity,
        )
    }
}
