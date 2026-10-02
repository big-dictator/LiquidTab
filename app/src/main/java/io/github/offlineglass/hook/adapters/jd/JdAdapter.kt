package io.github.offlineglass.hook.adapters.jd

import android.content.Context
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import android.view.Gravity

/** JD-specific policy flags live here; shared rendering remains package-agnostic. */
internal object JdAdapter : TargetAdapter {
    override val key = "jd"
    override val honorsBackdropCaptureSetting = true
    override val ownsNavigationDrawing = true
    override val navigationBehindIndicator = true
    override val stagedNavigationSnapshot = true
    override val ownsNavigationSnapshotCapturePolicy = true
    override val supportsScheduledNavigationSnapshotRefresh = true
    override val navigationSnapshotStableFrames = 1
    override val navigationSignatureMaxDepth = 12
    override val usesCommittedSelection = true
    override val usesContentDarkMode = true
    override val ownsOpticalSurfaceLayout = true
    override val redrawSurfaceAfterNavigationCapture = true
    override val sourceContentOffsetDp = -6f
    override fun createNavigationState(
        context: Context, host: io.github.offlineglass.hook.GlassHostLayout,
    ): AppNavigationState = JdNavigationState(host)
    override fun installHooks(
        lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec,
    ) = JdScrollSignals.install(lpparam.classLoader)

    override val reuseOpticalBlur = true
    override val allowsSystemBackgroundBlur = false
    override val countsOpticalCompositorCopies = true
    override val deferOpticalRenderDuringBarAnimation = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    override val usesContentAlignedOpticalSurface = true
    override val retainsExistingBackdropRenderNode = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val retainsOpticalSurfaceWhenBarHidden = true
    override val opticalSurfaceFollowsBarTransform = false
    override val usesDirectOpticalBackdropMode = true
    // JD always uses compositor pixels and the shared GPU blur. Native window
    // blur is disabled here: even its invisible helper Views cause layout and
    // RenderEffect invalidations when configured on each host pre-draw.
    override val usesNativeOpticalBlur = false
    override val repeatImmersionRestoreWhileInactive = true
    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): io.github.offlineglass.hook.adapters.OpticalCaptureGeometry? =
        JdCaptureGeometry.calculate(rootWidth, rootHeight, left, top, width, height, padding)
            ?.let {
                io.github.offlineglass.hook.adapters.OpticalCaptureGeometry(
                    it.sourceRect, it.targetWidth, it.targetHeight, it.barSource,
                )
            }

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
