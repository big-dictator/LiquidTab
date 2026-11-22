package io.github.offlineglass.hook.adapters.jd

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
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
    val checkoutController = JdCheckoutController(chromeFinder, backToTopController) { lastNativeTab == 3 }
    val flashControlsController = JdFlashControlsController(chromeFinder)
    val layoutProbe = JdLayoutProbe(host, chromeFinder, checkoutController, flashControlsController)
    val surfaceRenderQueue = OpticalSurfaceRenderQueue()
    private val gpuBackdrop = JdGpuBackdropCapture(host)
    private val pagePreview = JdPagePreview(host)
    private val tabTouch = JdTabTouch(host, pagePreview)
    private var opticalThread: HandlerThread? = null
    private var opticalHandler: Handler? = null
    private var browseExitRow: java.lang.ref.WeakReference<ViewGroup>? = null
    private var lastSnapshotProbeAt = 0L
    private var lastSnapshotProbeSource: ViewGroup? = null
    private var lastUiBarAnimating = false
    private var lostWindowFocus = false
    private var nativeBackdropScene: android.view.View? = null
    private var previewSceneRevision = -1
    private var backdropGeometry = Long.MIN_VALUE
    private val backdropSceneLocation = IntArray(2)
    private val backdropHostLocation = IntArray(2)
    private var backdropOffsetX = Int.MIN_VALUE
    private var backdropOffsetY = Int.MIN_VALUE
    private val nativeSceneSelector = JdNativeSceneSelector()
    private var lastNativeTab = -1
    private var frameListenerRoot: ViewGroup? = null
    private var interactionFrameRate = 0f
    private val releaseFrameRate = Runnable {
        if (android.os.Build.VERSION.SDK_INT >= 35 && interactionFrameRate != 0f) {
            interactionFrameRate = 0f
            host.setRequestedFrameRate(0f)
        }
    }

    private fun prioritizeInteractionFrames() {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        if (interactionFrameRate == 0f) {
            interactionFrameRate = host.display?.supportedModes?.maxOfOrNull { it.refreshRate }?.coerceAtMost(120f) ?: 120f
            host.setRequestedFrameRate(interactionFrameRate)
        }
        host.removeCallbacks(releaseFrameRate)
        host.postDelayed(releaseFrameRate, 700L)
    }

    override fun onBarGesture(phase: Int, host: android.view.View) = prioritizeInteractionFrames()

    override fun suppressNativeChromeEarly(source: ViewGroup?) {
        if (!host.config.enabled) return
        val row = JdNavigationBinding.resolve(source) ?: return
        nativeChrome.hideBrowse(row)
        JdMessageClassLookup.bindHost(host)
        JdHomeColdBatch.bindHost(host)
        if (artwork.jdStaticIconsPopulated) JdNativePageTransitions.bindHiddenBar(row.view, host)
        val nativeSelected = row.selected()
        if (nativeSelected == JdNavigationBinding.BROWSE) {
            if (host.hasWindowFocus() && browseExitRow?.get() !== row.view) {
                browseExitRow = java.lang.ref.WeakReference(row.view)
                host.post {
                    if (host.isAttachedToWindow && host.config.enabled && host.hasWindowFocus() &&
                        host.navigationSource === row.view && row.isCurrent() && row.selected() == JdNavigationBinding.BROWSE)
                        tabTouch.dispatch(row.view, JdNavigationBinding.HOME, row.slots.size)
                }
            }
        } else browseExitRow = null
        val actual = nativeSelected ?: host.navigationIndex
        if (nativeSelected != null && nativeSelected != lastNativeTab) {
            lastNativeTab = nativeSelected
            pageState.onNativeTabChanged(nativeSelected)
            contentViewport.invalidateGeometry()
            repairNativeChrome()
        }
        // Keep JD's page independent from its native nav BEFORE the first
        // destination draw, including the host's 240ms animation-priority phase.
        source?.let { JdClassicLayout.adjust(host, it, !pageState.newProductsActive) }
        checkoutController.setPreviewScene(pagePreview.scene.takeIf { pagePreview.target == 3 })
        checkoutController.syncPageVisibility(pagePreview.target.takeIf { it >= 0 } ?: actual)
        // This callback runs before drawing even when ordinary maintenance is
        // deferred for the slider. Bind a newly mounted footer on its first frame.
        if (actual == 3) (host.rootView as? ViewGroup)?.let { root ->
            checkoutController.lift(root, root.height, host)
        }
    }

    override fun onHostFrame(host: android.view.View) {
        val glassHost = host as? GlassHostLayout ?: return
        // The host's direct-backdrop pre-draw listener already updates optics.
        // Checkout is corrected in suppressNativeChromeEarly, before drawing.
        val root = glassHost.rootView as? ViewGroup ?: return
        if (frameListenerRoot !== root) {
            frameListenerRoot = root
            JdScrollSignals.bind(root, this)
            layoutProbe.attach(
                root,
                selectedIndex = { lastNativeTab.takeIf { it >= 0 } ?: glassHost.navigationIndex },
                newProductsActive = { pageState.newProductsActive },
                onContentScroll = glassHost::onAdapterContentScroll,
                refreshNewProducts = ::refreshNewProducts,
            )
        }
        contentViewport.adjust(
            glassHost, glassHost.navigationSource, root, pageState.newProductsActive,
        ) { contentRoot, rootHeight ->
            if (!pageState.newProductsActive && lastNativeTab == JD_HOME_INDEX) {
                flashControlsController.lift(contentRoot, rootHeight, glassHost)
            }
        }
    }

    override fun onContentScroll() {
        pageState.contentMotionUntil = android.os.SystemClock.uptimeMillis() +
            JdPageState.CONTENT_MOTION_HOLD_MS
        if (pageState.newProductsActive) pageState.newProductsSnapshotDirty = true
    }

    override fun refreshNavigationSource(host: android.view.View): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        val current = glassHost.navigationSource
        if (current != null && current.isAttachedToWindow && current.isShown && current.width > 0 &&
            JdNavigationBinding.resolve(current) != null) return false
        val content = glassHost.activityForAdapter()?.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val view = JdNavigationBinding.find(content)?.view ?: return false
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

    private fun refreshNewProducts(root: ViewGroup, now: Long) {
        val wasActive = pageState.newProductsActive
        val probe = pageState.probeNewProducts(root, lastNativeTab.takeIf { it >= 0 } ?: host.navigationIndex, now)
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
        if (artwork.jdStaticIconsPopulated && !artwork.artworkRefreshPending) {
            if (!touching && !visualAnimating && snapshot?.isRecycled == false) {
                artwork.prepare(source, slotCount)
                artwork.refreshBadges(snapshot)
            }
            return true
        }
        return touching || visualAnimating || !shouldProbeSnapshot(source, now)
    }

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

    override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = surfaceActive || nativeBackdropScene != null

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

    override fun drawHiddenTabsInIndicatorScene(hybridBackdrop: Boolean): Boolean = false

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
        // No verified live scene means readback mode, including custom H5
        // pages on Home. Falling through to the host's generic scene while
        // its Surface starts would invoke the WebView functor a second time.
        nativeBackdropScene == null

    override fun requiresSampledBackdrop(): Boolean =
        host.config.enabled && host.config.backdropCapture && !host.config.solidBarEnabled &&
            (host.config.liquidGlassEnabled || host.config.blurRadius > 0f)

    override fun allowsOpticalSurfacePipeline(): Boolean =
        nativeBackdropScene == null && requiresSampledBackdrop()
    override fun preparesOpticalSurfaceForWindowCapture(): Boolean = true
    override fun opticalCaptureEpoch(): Long = opticalCache.backdropEpoch.toLong()
    // Only the adapter-owned GPU capture can feed this Surface.
    override fun pauseOpticalSurfaceProbe(): Boolean = true

    override fun opticalSurfaceCopyHandler(): Handler {
        opticalHandler?.let { return it }
        val thread = HandlerThread("LiquidTab-JD-Optics").also { it.start() }
        opticalThread = thread
        return Handler(thread.looper).also { opticalHandler = it }
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
            host.rootView as? ViewGroup, host.opticalSurfaceViewForAdapter, lastNativeTab,
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
        val nativeScene = pagePreview.scene ?: if (requiresSampledBackdrop()) nativeSceneSelector.pick(
            host, lastNativeTab.takeIf { it >= 0 } ?: host.navigationIndex,
            pageState.newProductsActive || pageState.fullscreenVideoActive,
        ) else null
        nativeScene?.getLocationInWindow(backdropSceneLocation)
        host.getLocationInWindow(backdropHostLocation)
        val dx = backdropSceneLocation[0] - backdropHostLocation[0]
        val dy = backdropSceneLocation[1] - backdropHostLocation[1]
        if (backdropOffsetX == Int.MIN_VALUE || kotlin.math.abs(dx - backdropOffsetX) > 1) backdropOffsetX = dx
        if (backdropOffsetY == Int.MIN_VALUE || kotlin.math.abs(dy - backdropOffsetY) > 1) backdropOffsetY = dy
        var geometry = host.width.toLong() * 31L + host.height
        geometry = geometry * 31L + (nativeScene?.width ?: 0)
        geometry = geometry * 31L + (nativeScene?.height ?: 0)
        geometry = geometry * 31L + backdropOffsetX
        geometry = geometry * 31L + backdropOffsetY
        if (nativeScene !== nativeBackdropScene || previewSceneRevision != pagePreview.sceneRevision || backdropGeometry != geometry) {
            backdropGeometry = geometry
            previewSceneRevision = pagePreview.sceneRevision
            nativeBackdropScene = nativeScene
            gpuBackdrop.cancel()
            // Preview and real pages can share the same tab and bitmap-copy
            // generation. None of the retained blur/indicator wrappers may
            // keep referencing the preview RenderNode after it becomes GONE.
            opticalCache.backdropEpoch++
            opticalCache.captureGeneration++
            opticalCache.outerRecordedGeneration = -1L
            opticalCache.sharedOpticalGeneration = -1L
            opticalCache.indicatorCombinedGeneration = -1L
            opticalCache.liveBackdropEpoch = -1
            opticalCache.liveBackdropTab = -1
            host.liveBackdropNodeForAdapter.discardDisplayList()
            host.clearLiveBackdropForAdapter()
            // Dirty the host's own display list as well as its sampled child.
            // A root scroll/layout need not redraw this otherwise-static View.
            host.invalidate()
        }
        if (nativeScene != null) {
            host.disableOpticalSurfaceNativeBlurForAdapter()
            host.disableOpticalSurfaceRendererForAdapter()
            // Android owns the native page RenderNodes. The host records a
            // narrow live reference and draws optics in the same window frame.
            return true
        }
        if (!requiresSampledBackdrop()) {
            gpuBackdrop.cancel()
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
        gpuBackdrop.request(opticalCaptureEpoch(), ::opticalSurfaceCopyHandler)
        return true
    }

    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean =
        selectedIndex == JD_BROWSE_INDEX && artwork.jdStaticIconsPopulated

    override fun retainNavigationWithoutNativeSource(selectedIndex: Int): Boolean =
        artwork.jdStaticIconsPopulated || pagePreview.target >= 0 || tabTouch.pendingTarget >= 0 ||
            pageState.fullscreenVideoActive || retainNavigationWhenNativeRowHidden(selectedIndex)

    override fun resolveBackdropScene(host: android.view.View): android.view.View? = nativeBackdropScene

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<android.view.View>? =
        nativeBackdropScene?.let { listOf(it) }

    override fun canReuseLiveBackdrop(selectedIndex: Int): Boolean =
        nativeBackdropScene != null && opticalCache.liveBackdropEpoch == opticalCache.backdropEpoch &&
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
        if (!pipelineActive && surface.visibility == android.view.View.GONE) return false
        surface.visibility = android.view.View.GONE
        opticalCache.surfaceFrameReady = false
        opticalCache.directBackdropActive = false
        return true
    }

    override fun onOpticalSurfaceFrameInvalidated() {
        opticalCache.surfaceFrameReady = false
        host.postInvalidateOnAnimation()
    }

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
    ): Boolean {
        // A previously posted buffer is not proof of current presentation.
        // Surface teardown/visibility callbacks and page-mode changes can
        // arrive in a different order. Never surrender host drawing to a GONE,
        // invalid, or no-longer-owned compositor layer (shadow-only failure).
        val surface = host.opticalSurfaceViewForAdapter
        return nativeBackdropScene != null || !opticalCache.surfaceFrameReady ||
            !host.opticalSurfaceRunningForAdapter || surface?.visibility != android.view.View.VISIBLE ||
            !surface.isShown
    }

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
        JdNavigationBinding.resolve(source)?.let { nativeChrome.suppress(it.view) }
    }

    override val readyForDrawing: Boolean
        get() = artwork.jdStaticIconsPopulated

    override fun onNavigationTabSwitched(index: Int, slotCount: Int, now: Long) {
        opticalCache.indicatorCombinedGeneration = -1
        pageState.tabSwitchUntil = now + TAB_SWITCH_HOLD_MS
        pageState.newProductsSnapshotDirty = true
    }

    override fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean {
        opticalCache.indicatorCombinedGeneration = -1
        selectionFollow.start(index, slotCount, now)
        opticalCache.backdropEpoch++
        return true
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? = tabTouch.selected(source, slotCount)

    override fun performTap(host: android.view.View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        prioritizeInteractionFrames()
        val handled = tabTouch.dispatch(source, index, slotCount)
        if (handled) finishNativeTap(index, slotCount)
        return handled
    }

    private fun finishNativeTap(index: Int, slotCount: Int) {
        onNavigationSelectionRequested(index, slotCount, android.os.SystemClock.uptimeMillis())
        host.postInvalidateOnAnimation()
    }

    override fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean {
        selectionFollow.acceptNativeSelection(index, now)
        // Only a live, bounded native-click request may hold off its old state.
        // Once accepted, exhausted, cancelled or detached, native state wins.
        return tabTouch.pendingTarget < 0 || index == tabTouch.pendingTarget
    }

    override fun followingNativeNavigationAnimation(now: Long): Boolean = selectionFollow.isFollowing(now)

    override fun pendingNativeNavigationTarget(now: Long): Int? =
        selectionFollow.target.takeIf { selectionFollow.isAwaitingSelection(now) }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = artwork.draw(canvas, frame)


    override fun dispose() {
        pagePreview.clear(dispose = true)
        artwork.dispose()
        selectionFollow.reset()
        lastSnapshotProbeSource = null
        lastSnapshotProbeAt = 0L
    }

    override fun onHostDetached() {
        lastNativeTab = -1
        frameListenerRoot = null
        JdClassicLayout.release(host)
        host.removeCallbacks(releaseFrameRate)
        releaseFrameRate.run()
        tabTouch.cancel()
        JdScrollSignals.unbind(this)
        lostWindowFocus = false
        nativeBackdropScene = null
        nativeSceneSelector.detach()
        gpuBackdrop.cancel()
        selectionFollow.reset()
        browseExitRow = null
        opticalThread?.quitSafely()
        opticalThread = null
        opticalHandler = null
        pageState.reset()
        layoutProbe.detach()
        backToTopController.restore()
        checkoutController.restore()
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
        nativeSceneSelector.onFocusChanged(hasFocus)
        if (!hasFocus) pagePreview.clear()
        if (!hasFocus) {
            gpuBackdrop.cancel()
            host.removeCallbacks(releaseFrameRate)
            releaseFrameRate.run()
            tabTouch.cancel()
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
        const val TAB_SWITCH_HOLD_MS = 180L
        const val JD_BROWSE_INDEX = 1
        const val JD_HOME_INDEX = 0
    }
}
