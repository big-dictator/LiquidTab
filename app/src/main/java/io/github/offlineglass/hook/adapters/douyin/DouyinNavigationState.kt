package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AppVideoBackdrop

/** All per-host Douyin navigation and page state lives with the Douyin adapter. */
internal class DouyinNavigationState(context: Context) : AppNavigationState {
    private val renderer = DouyinNavigationRenderer(context)
    private val pageController = DouyinPageController(context)
    private val videoSampler = DouyinVideoSampler(context, pageController::logDiagnostic)
    private var outerRecordedVideoEpoch = Int.MIN_VALUE
    private var outerRecordedBackdrop: Bitmap? = null
    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0
    private var outerRecordedSamplingBlocked = false
    override val blocksBackdropSampling: Boolean get() = pageController.shopPageActive

    // The shared host retains its outer RenderNode while the live view scene is
    // active. Douyin's PixelCopy bitmap is painted into that node by value, so
    // a new video frame must invalidate the node even though the view tree and
    // the host's ordinary backdrop bitmap have not changed. The indicator
    // records every frame independently; without this policy only it moves.
    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean = !hasDisplayList ||
        outerRecordedVideoEpoch != (videoSampler.snapshot()?.epoch ?: -1) ||
        outerRecordedBackdrop !== backdrop ||
        outerRecordedWidth != width || outerRecordedHeight != height ||
        outerRecordedSamplingBlocked != blocksBackdropSampling

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerRecordedVideoEpoch = videoSampler.snapshot()?.epoch ?: -1
        outerRecordedBackdrop = backdrop
        outerRecordedWidth = width
        outerRecordedHeight = height
        outerRecordedSamplingBlocked = blocksBackdropSampling
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = renderer.draw(canvas, frame)
    override fun logDiagnostic(message: String) = pageController.logDiagnostic(message)
    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) =
        videoSampler.request(host, pageAllowed, blocksBackdropSampling)
    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.snapshot()
    override fun pageAllowsNavigation(root: View?): Boolean = true

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        renderer.drawForeground(canvas, frame, selectionPath, drawSource)
        return true
    }

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) = pageController.update(host, barHeightPx)

    override fun onHostDetached() {
        pageController.dispose()
        videoSampler.dispose()
    }
}
