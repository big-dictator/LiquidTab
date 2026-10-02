package io.github.offlineglass.hook.adapters.taobao

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import io.github.offlineglass.hook.GlassHostLayout

/** Per-host Taobao compositor state, isolated from the shared glass renderer. */
internal class TaobaoNavigationState(context: Context) : AppNavigationState {
    private val artwork = TaobaoArtwork(context)
    private val badge = TaobaoBadge()
    internal val scene = TaobaoScene()
    private val sampler = TaobaoHomeSurfaceSampler()
    private val pageChrome = TaobaoPageChrome()
    private var selectedIndex = 0
    private var flashSale = false
    private var opticalThread: HandlerThread? = null
    private var opticalHandler: Handler? = null
    @Volatile private var surfaceRedrawPending = true
    private var currentHost: GlassHostLayout? = null
    private data class TouchableState(val clickable: Boolean, val focusable: Boolean)
    private val touchableStates = java.util.WeakHashMap<View, TouchableState>()

    override fun setNativeBarTouchable(view: View?, touchable: Boolean): Boolean {
        view ?: return true
        if (touchable) {
            touchableStates.remove(view)?.let {
                view.isClickable = it.clickable
                view.isFocusable = it.focusable
            }
        } else {
            touchableStates.getOrPut(view) { TouchableState(view.isClickable, view.isFocusable) }
            view.isClickable = false
            view.isFocusable = false
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setNativeBarTouchable(view.getChildAt(index), touchable)
        }
        return true
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val row = source?.takeIf { it.isAttachedToWindow && it.width > 0 && it.height > 0 } ?: return false
        val bounded = index.coerceIn(0, (slotCount - 1).coerceAtLeast(0))
        val x = row.width * (bounded + 0.5f) / slotCount.coerceAtLeast(1)
        val y = row.height * 0.5f
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 48L, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val acceptedDown = row.dispatchTouchEvent(down)
            val acceptedUp = row.dispatchTouchEvent(up)
            acceptedDown || acceptedUp
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun updatePageChrome(host: View, source: ViewGroup?) {
        selectedIndex = (host as? GlassHostLayout)?.adapterSelectedIndex ?: selectedIndex
        probeScene(host)
        flashSale = scene.flashSaleChannelActive
        if (selectedIndex == 0) pageChrome.adjustCoupon(host, source, flashSale)
        pageChrome.adjustFlashSaleControls(host, source, selectedIndex == 0 && flashSale)
        pageChrome.adjustCart(host, selectedIndex)
    }

    fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            (host as? GlassHostLayout)?.resetCompositorSamplingCadence()
        }
        pageChrome.onWindowTouch(host, event, flashSale)
    }

    override fun onHostPreDraw(
        host: View,
        source: ViewGroup?,
        selected: () -> Int,
        enabled: Boolean,
        
        density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        selectedIndex = selected()
    }

    override fun onHostFrame(host: View) {
        currentHost = host as? GlassHostLayout
        probeScene(host)
        (host as? GlassHostLayout)?.let(::updateBackdropRendererMode)
    }

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        // Cart also embeds Unicorn. Replaying its retained page RenderNodes
        // from the glass must not drive a second engine draw/Surface lifecycle.
        val enabled = (host.adapterSelectedIndex == 0 || host.adapterSelectedIndex == 3) &&
            !host.config.solidBarEnabled
        if (enabled) {
            host.disableOpticalSurfaceNativeBlurForAdapter()
            if (host.liveBackdropActiveForAdapter || host.liveBackdropNodeForAdapter.hasDisplayList()) {
                host.liveBackdropNodeForAdapter.setRenderEffect(null)
                host.liveBackdropNodeForAdapter.discardDisplayList()
                host.clearLiveBackdropForAdapter()
            }
        }
        host.updateOpticalSurfacePipeline(enabled)
        return true
    }

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = selectedIndex == 0 || selectedIndex == 3

    override fun opticalSurfaceCopyHandler(): Handler {
        opticalHandler?.let { return it }
        return HandlerThread("Taobao-Optical").let { thread ->
            thread.start()
            opticalThread = thread
            Handler(thread.looper).also { opticalHandler = it }
        }
    }

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? {
        // Both sources submit asynchronously on the optical worker. A page
        // Surface delivery queues directly; UI submits only for animation or
        // navigation/configuration changes.
        val queue = animating || surfaceRedrawPending
        // Consume on the UI thread before queuing. A navigation/config update
        // arriving during the worker render must remain pending for next draw.
        if (queue) surfaceRedrawPending = false
        return queue
    }

    override fun onOpticalRendererConfigurationChanged() { surfaceRedrawPending = true }
    override fun onOpticalSurfaceFrameInvalidated() { surfaceRedrawPending = true }
    override fun onContentScroll() { currentHost?.resetCompositorSamplingCadence() }

    private fun probeScene(host: View) {
        selectedIndex = (host as? GlassHostLayout)?.adapterSelectedIndex ?: selectedIndex
        sampler.probe(host, selectedIndex == 0)
        val root = host.rootView as? ViewGroup ?: return
        val change = scene.probe(root) ?: return
        if (!change.flashSaleChanged) return
        val glass = host as? GlassHostLayout ?: return
        if (change.flashSale) glass.adapterOnScrollStopHidePageEntered(3_000L)
        else glass.adapterOnScrollStopHidePageExited()
    }

    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
        val glass = host as? GlassHostLayout
        sampler.request(host, pageAllowed && selectedIndex == 0,
            scene.fliggyChannelActive,
            scene.flashSaleChannelActive && glass?.barVisibilityAnimating == true &&
                !glass.barVisibilityTarget)
    }

    override fun videoBackdrop(): AppVideoBackdrop? = sampler.snapshot()

    override fun acceptCapturedNavigation(source: ViewGroup?, target: Bitmap, selectedIndex: Int): Boolean =
        artwork.acceptCandidate(source, target, selectedIndex).also { accepted ->
            if (accepted) surfaceRedrawPending = true
        }

    override fun replaceCapturedVideoSlot(target: Bitmap) = artwork.replaceVideoSlot(target)


    override fun drawFixedNavigationSlot(canvas: Canvas, index: Int, selectedIndex: Int,
                                         dark: Boolean, centerX: Float, centerY: Float, boxSize: Float): Boolean =
        if (index == 0 && selectedIndex == 0) false
        else artwork.drawFixedSlot(canvas, index, selectedIndex, dark, centerX, centerY, boxSize)

    override fun sourceContentOffsetY(index: Int, selectedIndex: Int, hostHeight: Int,
                                      transformDy: Float, transformScale: Float): Float? =
        if (index == 0 && selectedIndex == 0 && artwork.selectedHomeContentCenterY.isFinite())
            hostHeight / 2f - (transformDy + artwork.selectedHomeContentCenterY * transformScale)
        else 0f

    override fun drawAppBadgeOverlay(canvas: Canvas, host: View, contentScale: Float) {
        (host as? GlassHostLayout)?.let { badge.draw(canvas, it, contentScale) }
    }

    override fun needsSurfaceBackdrop(): Boolean = sampler.hasSurface

    override fun retainBackdropDuringTransition(now: Long): Boolean = now < scene.barPriorityUntil

    override fun retainHomeBackdrop(selectedIndex: Int): Boolean = selectedIndex == 0

    override fun deferThemeDetection(now: Long): Boolean = now < scene.barPriorityUntil

    override fun needsContinuousHomeFrames(selectedIndex: Int): Boolean = selectedIndex == 0

    override fun pageRequiresScrollStopHide(selectedIndex: Int): Boolean =
        selectedIndex == 0 && scene.flashSaleChannelActive

    override fun onBarGesture(phase: Int, host: View) {
        val now = SystemClock.uptimeMillis()
        scene.barPriorityUntil = now + if (phase == 2) 520L else 180L
        if (phase == 0 && scene.flashSaleChannelActive)
            (host as? GlassHostLayout)?.adapterCancelScrollStopHide()
        if (phase == 2) (host as? GlassHostLayout)?.adapterScheduleBackdropRefresh(536L)
    }

    override fun onHostDetached() {
        currentHost = null
        opticalThread?.quitSafely()
        opticalThread = null
        opticalHandler = null
        surfaceRedrawPending = true
        sampler.dispose()
        artwork.dispose()
        scene.reset()
        pageChrome.dispose()
        touchableStates.clear()
    }

}
