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
    private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.argb(28, 255, 255, 255)
    }
    private var haloDensity = 0f
    override fun drawPanelEdgeGlow(canvas: Canvas, path: Path, density: Float) {
        if (density != haloDensity) {
            haloDensity = density
            haloPaint.strokeWidth = 0.8f * density
            haloPaint.setShadowLayer(18f * density, 0f, 0f, android.graphics.Color.argb(45, 255, 255, 255))
        }
        // The supplied path includes both surfaces when the action is detached.
        canvas.drawPath(path, haloPaint)
    }

    private val renderer = DouyinNavigationRenderer(context)
    private val pageController = DouyinPageController(context)
    private val videoSampler = DouyinVideoSampler(context, pageController::logDiagnostic)
    private var outerRecordedVideoEpoch = Int.MIN_VALUE
    private var outerRecordedBackdrop: Bitmap? = null
    private var outerRecordedWidth = 0
    private var outerRecordedHeight = 0
    private var outerRecordedSamplingBlocked = false
    private var messageInputId = 0
    private var messageInputRef = java.lang.ref.WeakReference<View>(null)
    private var chromeRoot = java.lang.ref.WeakReference<View>(null)
    private val messageInputRect = android.graphics.Rect()
    private fun messageComposerVisible(root: View?): Boolean {
        root ?: return false
        if (messageInputId == 0) {
            messageInputId = root.resources.getIdentifier("msg_et", "id", "com.ss.android.ugc.aweme")
        }
        if (messageInputId == 0) return false
        var input = messageInputRef.get()
        if (input == null || !input.isAttachedToWindow || input.rootView !== root) {
            input = root.findViewById<View>(messageInputId)
            messageInputRef = java.lang.ref.WeakReference(input)
        }
        return input != null && input.isShown && input.alpha > 0.01f &&
            input.getGlobalVisibleRect(messageInputRect) && !messageInputRect.isEmpty
    }
    override val blocksBackdropSampling: Boolean get() = pageController.shopPageActive
    override fun pauseBackdropWork(host: View): Boolean =
        (pageController.profilePageActive() || pageController.messageComposerVisible() ||
            DouyinSidebarPages.isVisible(host.rootView)) &&
            (!host.isShown || host.alpha <= 0.001f)

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
        videoSampler.request(host, pageAllowed, blocksBackdropSampling || pageController.grouponPageActive)
    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.snapshot()
    override fun pageAllowsNavigation(root: View?): Boolean =
        !pageController.messageComposerVisible() && !pageController.profilePageActive() &&
            !DouyinSidebarPages.isVisible(root)

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        renderer.drawForeground(canvas, frame, selectionPath, drawSource)
        return true
    }

    override fun forceNavigationBarImmersed(host: io.github.offlineglass.hook.GlassHostLayout) =
        !DouyinCommentLayoutHooks.requiresNativeNavigationFit(host.rootView)

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        // Freeze homepage reservations while the native drawer owns the
        // viewport. Visibility still uses the shared animated exit/entry path.
        if (chromeRoot.get() !== host.rootView) chromeRoot = java.lang.ref.WeakReference(host.rootView)
        if (!DouyinSidebarPages.isVisible(host.rootView)) pageController.update(host, barHeightPx)
    }

    override fun onHostDetached() {
        DouyinBottomDecorations.restore(chromeRoot.get())
        pageController.dispose()
        videoSampler.dispose()
    }
}


