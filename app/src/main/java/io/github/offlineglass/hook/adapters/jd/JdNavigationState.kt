package io.github.offlineglass.hook.adapters.jd

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.NavigationCaptureLabels
import io.github.offlineglass.hook.adapters.OpticalSurfaceRenderQueue
import io.github.offlineglass.hook.GlassHostLayout

/** JD's native slot snapshots belong to this app, not the shared host. */
internal class JdNavigationState(private val host: GlassHostLayout) : AppNavigationState {
    val artwork = JdArtwork()
    val pageState = JdPageState()
    val opticalCache = JdOpticalCache()
    val selectionFollow = JdSelectionFollow()
    val nativeChrome = JdNativeChrome(host)
    val chromeFinder = JdChromeFinder(host)
    val contentViewport = JdContentViewport()
    val backToTopController = JdBackToTopController()
    val checkoutController = JdCheckoutController(chromeFinder, backToTopController)
    val flashControlsController = JdFlashControlsController(chromeFinder)
    val layoutProbe = JdLayoutProbe(host, chromeFinder, checkoutController, flashControlsController)
    val surfaceRenderQueue = OpticalSurfaceRenderQueue()
    private val frameCopyScheduler = JdFrameCopyScheduler()
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null
    private var navigationGroupClass: Class<*>? = null
    private var lastSnapshotProbeAt = 0L
    private var lastSnapshotProbeSource: ViewGroup? = null
    private var lastUiBarAnimating = false
    private var lostWindowFocus = false
    private var lastPageIndex = -1

    override fun onHostFrame(host: android.view.View) {
        val glassHost = host as? GlassHostLayout ?: return
        updateBackdropRendererMode(glassHost)
        if (lastPageIndex != glassHost.navigationIndex) {
            lastPageIndex = glassHost.navigationIndex
            contentViewport.invalidateGeometry()
            repairNativeChrome()
        }
        val root = glassHost.rootView as? ViewGroup ?: return
        JdScrollSignals.bind(root, this)
        layoutProbe.attach(
            root,
            selectedIndex = { glassHost.navigationIndex },
            newProductsActive = { pageState.newProductsActive },
            onContentScroll = glassHost::onAdapterContentScroll,
            refreshNewProducts = { root, now -> refreshNewProducts(glassHost, root, now) },
        )
        checkoutController.correct(root, glassHost)
        contentViewport.adjust(
            glassHost, glassHost.navigationSource, root, pageState.newProductsActive,
        ) { contentRoot, rootHeight ->
            checkoutController.lift(contentRoot, rootHeight, glassHost)
            if (!pageState.newProductsActive) flashControlsController.lift(contentRoot, rootHeight, glassHost)
        }
    }

    override fun onOpticalBackdropChanged() = onContentScroll()

    override fun onContentScroll() {
        pageState.contentMotionUntil = android.os.SystemClock.uptimeMillis() +
            JdPageState.CONTENT_MOTION_HOLD_MS
        if (pageState.newProductsActive) pageState.newProductsSnapshotDirty = true
        host.resetCompositorSamplingCadence()
    }

    override fun refreshNavigationSource(host: android.view.View): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        val current = glassHost.navigationSource
        if (current != null && current.isAttachedToWindow && current.width > 0) return false
        val content = glassHost.activityForAdapter()?.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val view = chromeFinder.findNavigationGroup(content, navigationGroupClass) ?: return false
        navigationGroupClass = view.javaClass
        glassHost.navigationSource = view
        checkoutController.onNavigationRebound()
        flashControlsController.nextFindAt = 0L
        nativeChrome.suppress(view)
        glassHost.postOnAnimation {
            if (glassHost.isAttachedToWindow && glassHost.navigationSource === view) {
                contentViewport.invalidateAdjustedSource()
                onHostFrame(glassHost)
            }
        }
        glassHost.postInvalidateOnAnimation()
        return true
    }

    fun refreshNewProducts(host: GlassHostLayout, root: ViewGroup, now: Long) {
        val wasActive = pageState.newProductsActive
        val probe = pageState.probeNewProducts(root, host.navigationIndex, now)
        if (!probe.changed) return
        repairNativeChrome()
        if (probe.active && !wasActive) {
            pageState.newProductsSnapshotDirty = true
            contentViewport.restoreForNewProducts()
            flashControlsController.clearForNewProducts(
                now, JdPageState.FLASH_INACTIVE_PROBE_BACKOFF_MS,
            )
        } else if (!probe.active && wasActive) {
            pageState.newProductsSnapshotDirty = true
            contentViewport.invalidateGeometry()
        }
    }

    override fun contentMotionHoldUntil(): Long = pageState.contentMotionUntil

    fun shouldProbeSnapshot(source: ViewGroup, now: Long): Boolean {
        if (lastSnapshotProbeSource === source && now - lastSnapshotProbeAt < SNAPSHOT_PROBE_INTERVAL_MS)
            return false
        lastSnapshotProbeSource = source
        lastSnapshotProbeAt = now
        return true
    }

    override fun skipNavigationSnapshotCapture(
        source: ViewGroup, snapshot: android.graphics.Bitmap?, touching: Boolean,
        visualAnimating: Boolean, slotCount: Int, now: Long,
    ): Boolean {
        artwork.prepare(source, slotCount)
        if (artwork.jdStaticIconsPopulated) {
            if (!touching && !visualAnimating && snapshot?.isRecycled == false) {
                artwork.prepare(source, slotCount)
                artwork.refreshBadges(snapshot)
            }
            return true
        }
        return !shouldProbeSnapshot(source, now)
    }

    override fun inspectNavigationCaptureLabels(source: ViewGroup, hasSnapshot: Boolean): NavigationCaptureLabels {
        val inspection = artwork.inspectCaptureLabels(source, hasSnapshot)
        return NavigationCaptureLabels(
            inspection.labels,
            if (inspection.retryLater) LABEL_REBIND_BACKOFF_MS else 0L,
        )
    }

    override fun temporarilyHideNavigationLabels(
        labels: List<TextView>, selectedIndex: Int,
    ): List<Pair<TextView, Int>> = artwork.labelsHiddenDuringHomeCapture(labels, selectedIndex)

    override fun acceptAndCommitNavigationSnapshot(
        source: ViewGroup?, target: Bitmap, selectedIndex: Int, slotCount: Int,
        commit: (Bitmap) -> Unit,
    ): Boolean {
        artwork.prepare(source, slotCount)
        if (!artwork.accept(target, selectionFollow.target < 0)) return false
        commit(target)
        return true
    }

    override fun drawSampledBackdrop(
        canvas: Canvas, bitmap: Bitmap, destination: RectF, paint: Paint,
    ): Boolean {
        val source = opticalCache.backdropBarSourceRect ?: return false
        if (source.width() <= 0 || source.height() <= 0) return false
        canvas.drawBitmap(bitmap, source, destination, paint)
        return true
    }

    override fun reuseOuterBackdropForSurface(surfaceActive: Boolean): Boolean = surfaceActive

    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean = opticalCache.outerRecordedBackdrop !== backdrop ||
        opticalCache.outerRecordedGeneration != opticalCache.captureGeneration ||
        opticalCache.outerRecordedWidth != width || opticalCache.outerRecordedHeight != height ||
        !hasDisplayList

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        opticalCache.outerRecordedBackdrop = backdrop
        opticalCache.outerRecordedGeneration = opticalCache.captureGeneration
        opticalCache.outerRecordedWidth = width
        opticalCache.outerRecordedHeight = height
    }

    override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = surfaceActive

    override fun combinedIndicatorSceneNeedsRecord(
        backdrop: Bitmap?, navigation: Bitmap?, signature: Long, width: Int, height: Int,
        dark: Boolean, hasDisplayList: Boolean,
    ): Boolean = opticalCache.indicatorCombinedBackdrop !== backdrop ||
        opticalCache.indicatorCombinedGeneration != opticalCache.captureGeneration ||
        opticalCache.indicatorCombinedNavigationBitmap !== navigation ||
        opticalCache.indicatorCombinedNavigationSignature != signature ||
        opticalCache.indicatorCombinedWidth != width || opticalCache.indicatorCombinedHeight != height ||
        opticalCache.indicatorCombinedDark != dark || !hasDisplayList

    override fun onCombinedIndicatorSceneRecorded(
        backdrop: Bitmap?, navigation: Bitmap?, signature: Long, width: Int, height: Int, dark: Boolean,
    ) {
        opticalCache.indicatorCombinedBackdrop = backdrop
        opticalCache.indicatorCombinedGeneration = opticalCache.captureGeneration
        opticalCache.indicatorCombinedNavigationBitmap = navigation
        opticalCache.indicatorCombinedNavigationSignature = signature
        opticalCache.indicatorCombinedWidth = width
        opticalCache.indicatorCombinedHeight = height
        opticalCache.indicatorCombinedDark = dark
    }

    override fun drawHiddenTabsInIndicatorScene(hybridBackdrop: Boolean): Boolean = true

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean = true

    override fun opticalBlurNodeNeedsRecord(
        prepared: Boolean, backdrop: Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?,
    ): Boolean = !prepared || opticalCache.sharedOpticalBackdrop !== backdrop ||
        opticalCache.sharedOpticalGeneration != opticalCache.captureGeneration ||
        opticalCache.sharedOpticalWidth != width || opticalCache.sharedOpticalHeight != height ||
        opticalCache.sharedOpticalEffect !== effect

    override fun onOpticalBlurNodeRecorded(
        backdrop: Bitmap?, width: Int, height: Int, effect: android.graphics.RenderEffect?,
    ) {
        opticalCache.sharedOpticalBackdrop = backdrop
        opticalCache.sharedOpticalGeneration = opticalCache.captureGeneration
        opticalCache.sharedOpticalWidth = width
        opticalCache.sharedOpticalHeight = height
        opticalCache.sharedOpticalEffect = effect
    }

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean =
        selectedIndex == JD_BROWSE_INDEX || pageState.fullscreenVideoActive

    override fun requiresSampledBackdrop(): Boolean =
        host.config.enabled && host.config.backdropCapture && !host.config.solidBarEnabled &&
            (host.config.liquidGlassEnabled || host.config.blurRadius > 0f)

    override fun maintainsCompositorSamplingWithoutSurface(): Boolean =
        requiresSampledBackdrop() && pageState.newProductsActive

    override fun opticalSurfaceCopyHandler(): Handler {
        pixelCopyHandler?.let { return it }
        val thread = HandlerThread("LiquidTab-JD-PixelCopy").also { it.start() }
        pixelCopyThread = thread
        return Handler(thread.looper).also { pixelCopyHandler = it }
    }

    override fun scheduleOpticalSurfaceCopy(root: android.view.View, copy: () -> Unit): Boolean =
        frameCopyScheduler.schedule(root, copy)

    override fun overlapsOpticalCopyAndRender(): Boolean = true

    override fun onOpticalSurfaceCopyCompleted(root: android.view.View, nextCopy: () -> Unit) {
        // Arm the next commit immediately after publication, before rendering the
        // current glass frame. PixelCopy callbacks and renders share one worker,
        // so publication stays serial while GPU readback can overlap submission.
        // Only a moving page chains copies. Arm before the next
        // UI traversal; idle probing backs off.
        root.post {
            if (requiresSampledBackdrop() && host.isAttachedToWindow && host.hasWindowFocus() &&
                android.os.SystemClock.uptimeMillis() < pageState.contentMotionUntil) {
                frameCopyScheduler.schedule(root, nextCopy)
            }
        }
    }

    override fun pageRequiresScrollStopHide(selectedIndex: Int): Boolean =
        pageState.fullscreenVideoActive || selectedIndex == JD_HOME_INDEX || selectedIndex == JD_BROWSE_INDEX

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: android.view.View?): Boolean {
        updateFullscreenVideoState(host)
        updateBrowseIdleTimer(host, false)
        return true
    }

    override fun forceNavigationBarImmersed(host: GlassHostLayout): Boolean =
        !updateFullscreenVideoState(host)

    fun updateFullscreenVideoState(host: GlassHostLayout): Boolean {
        val probe = pageState.probeFullscreenVideo(
            host.rootView as? ViewGroup, host.opticalSurfaceViewForAdapter,
        )
        if (probe.changed) host.onAdapterScrollHideTimerChanged(
            probe.active, JdPageState.VIDEO_IDLE_HIDE_DELAY_MS,
        )
        return probe.active
    }

    fun updateBrowseIdleTimer(host: GlassHostLayout, active: Boolean) {
        if (pageState.setBrowseIdleTimerActive(active)) host.onAdapterScrollHideTimerChanged(
            active, JdPageState.VIDEO_IDLE_HIDE_DELAY_MS,
        )
    }

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        if (!requiresSampledBackdrop()) {
            frameCopyScheduler.cancel()
            opticalCache.directBackdropActive = false
            opticalCache.surfaceFrameReady = false
            host.disableOpticalSurfaceNativeBlurForAdapter()
            host.disableOpticalSurfaceRendererForAdapter()
            // Render the translucent fill, outline, artwork and gestures in the
            // normal host frame. The real page is already visible underneath.
            return true
        }
        JdBackdropRuntime.activateCompositor(
            host.liveBackdropNodeForAdapter,
            host.liveBackdropActiveForAdapter,
            opticalCache,
            disableNativeBlur = host::disableOpticalSurfaceNativeBlurForAdapter,
            clearLiveHostState = host::clearLiveBackdropForAdapter,
            enableSurfaceRenderer = host::enableOpticalSurfaceRendererForAdapter,
        )
        return true
    }

    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean =
        selectedIndex == JD_BROWSE_INDEX && artwork.jdStaticIconsPopulated

    override fun retainNavigationWithoutNativeSource(selectedIndex: Int): Boolean =
        pageState.fullscreenVideoActive || retainNavigationWhenNativeRowHidden(selectedIndex)

    override fun canReuseLiveBackdrop(selectedIndex: Int): Boolean =
        opticalCache.liveBackdropEpoch == opticalCache.backdropEpoch &&
            opticalCache.liveBackdropTab == selectedIndex

    override fun onLiveBackdropRecorded(selectedIndex: Int) {
        opticalCache.liveBackdropEpoch = opticalCache.backdropEpoch
        opticalCache.liveBackdropTab = selectedIndex
    }

    override fun directOpticalBackdropActive(): Boolean = opticalCache.directBackdropActive

    override fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float): Boolean =
        hostVisibility == android.view.View.VISIBLE && hostAlpha > 0.001f

    override fun enterDirectOpticalBackdrop(
        surface: android.view.SurfaceView, pipelineActive: Boolean,
    ): Boolean {
        if (!requiresSampledBackdrop()) {
            // This mode paints in the host. Keeping a transparent Surface alive
            // and clearing it through lockCanvas can block every UI traversal.
            if (!pipelineActive && surface.visibility == android.view.View.GONE) return false
            surface.visibility = android.view.View.GONE
            opticalCache.surfaceFrameReady = false
            opticalCache.directBackdropActive = false
            return true
        }
        return JdSurfaceModeController.enterDirectMode(surface, opticalCache, pipelineActive)
    }

    override fun onOpticalSurfaceFrameInvalidated() { opticalCache.surfaceFrameReady = false }

    override fun onOpticalRendererConfigurationChanged() = nativeChrome.invalidateOuterEffect()

    override fun onOpticalSurfacePipelineEnabled(wasActive: Boolean) {
        opticalCache.directBackdropActive = false
        if (!wasActive) opticalCache.surfaceFrameReady = false
    }

    override fun onOpticalBackdropCaptured(barSource: android.graphics.Rect?) {
        opticalCache.backdropBarSourceRect = barSource
        opticalCache.captureGeneration++
    }

    override fun onOpticalSurfaceFrameRendered(): Boolean {
        if (opticalCache.surfaceFrameReady) return false
        opticalCache.surfaceFrameReady = true
        return true
    }

    override fun holdDirectBackdropUntilOpticalFrame(
        liveBackdropActive: Boolean, hasDisplayList: Boolean,
    ): Boolean = !opticalCache.surfaceFrameReady && liveBackdropActive && hasDisplayList

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean {
        val queue = animating || lastUiBarAnimating
        lastUiBarAnimating = animating
        return queue
    }

    override fun queueOpticalSurfaceRender(
        handler: android.os.Handler?, active: () -> Boolean, render: () -> Unit,
    ): Boolean {
        surfaceRenderQueue.request(handler, active, render)
        return true
    }

    override fun syncNativeSurfaceOuterEffect(
        view: android.view.View, liquidGlassEnabled: Boolean, width: Int, height: Int, blurRadius: Float,
        cornerRadiusPercent: Float, density: Float,
        shaderFactory: () -> android.graphics.RuntimeShader?,
    ): Boolean {
        nativeChrome.syncOuterEffect(
            view, liquidGlassEnabled, width, height, blurRadius, cornerRadiusPercent, density, shaderFactory,
        )
        return true
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        nativeChrome.suppress(source)
    }

    override val readyForDrawing: Boolean
        get() = artwork.jdStaticIconsPopulated

    override fun onNavigationTabSwitched(index: Int, slotCount: Int, now: Long) {
        pageState.tabSwitchUntil = now + TAB_SWITCH_HOLD_MS
        pageState.newProductsSnapshotDirty = true
    }

    override fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean {
        selectionFollow.start(index, slotCount, now)
        opticalCache.backdropEpoch++
        return true
    }

    override fun dispatchNativeNavigationTap(
        host: GlassHostLayout, source: ViewGroup?, index: Int, slotCount: Int,
    ): Boolean {
        val item = host.navigationSlotForAdapter(index) ?: return false
        return JdTabTouch.dispatch(item, source)
    }

    override fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean =
        selectionFollow.acceptNativeSelection(index, now)

    override fun followingNativeNavigationAnimation(now: Long): Boolean = selectionFollow.isFollowing(now)

    override fun pendingNativeNavigationTarget(now: Long): Int? =
        selectionFollow.target.takeIf { selectionFollow.isAwaitingSelection(now) }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = artwork.draw(canvas, frame)

    override fun dispose() {
        artwork.dispose()
        selectionFollow.reset()
        lastSnapshotProbeSource = null
        lastSnapshotProbeAt = 0L
    }

    override fun onHostDetached() {
        JdScrollSignals.unbind(this)
        lostWindowFocus = false
        lastPageIndex = -1
        frameCopyScheduler.cancel()
        selectionFollow.reset()
        navigationGroupClass = null
        pixelCopyThread?.quitSafely()
        pixelCopyThread = null
        pixelCopyHandler = null
        pageState.reset()
        layoutProbe.detach()
        backToTopController.restore()
        checkoutController.restore()
        checkoutController.discoveryExhausted = false
        flashControlsController.restore()
        nativeChrome.reset()
        nativeChrome.invalidateOuterEffect()
        contentViewport.restore()
        opticalCache.reset()
        opticalCache.backdropEpoch = 0
        opticalCache.liveBackdropEpoch = -1
        opticalCache.liveBackdropTab = -1
    }

    private fun repairNativeChrome() {
        nativeChrome.onWindowResumed {
            // JD can restore its shell measurements after the page switch.
            // Reapply once at that boundary, never on every scrolling frame.
            contentViewport.invalidateGeometry()
            onHostFrame(host)
        }
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) {
            lostWindowFocus = true
            return
        }
        if (!lostWindowFocus) return
        lostWindowFocus = false
        contentViewport.invalidateGeometry()
        repairNativeChrome()
        host.postInvalidateOnAnimation()
    }

    private companion object {
        const val SNAPSHOT_PROBE_INTERVAL_MS = 16L
        const val LABEL_REBIND_BACKOFF_MS = 240L
        const val TAB_SWITCH_HOLD_MS = 180L
        const val JD_BROWSE_INDEX = 1
        const val JD_HOME_INDEX = 0
    }
}
