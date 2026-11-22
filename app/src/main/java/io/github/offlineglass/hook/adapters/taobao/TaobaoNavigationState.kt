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
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.GlassHostLayout

/** Per-host Taobao compositor state, isolated from the shared glass renderer. */
internal class TaobaoNavigationState(context: Context, private val glassHost: GlassHostLayout) : AppNavigationState {
    private val artwork = TaobaoArtwork(context)
    private val badge = TaobaoBadge()
    internal val scene = TaobaoScene()
    private val frameCopyScheduler = TaobaoFrameCopyScheduler()
    private val pageChrome = TaobaoPageChrome()
    private val messageInsets = TaobaoMessageInsets()
    private val videoTabOverlay = TaobaoVideoTabOverlay()

    private val navigationVisibility = TaobaoNavigationVisibility()
    private val pagePreview = TaobaoPagePreview(glassHost)
    private val tabTransitions = TaobaoTabTransitions(glassHost, pagePreview)
    init { pagePreview.routeDiagnostic = { navigationVisibility.diagnostic() + " " + TaobaoNativePageTransactions.status() } }
    @Volatile private var navigationPixels: Bitmap? = null
    private val navigationPaint = object : ThreadLocal<android.graphics.Paint>() {
        override fun initialValue() = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    }
    private var selectedIndex = 0
    private var flashSale = false
    private var opticalThread: HandlerThread? = null
    private var opticalHandler: Handler? = null
    @Volatile private var surfaceRedrawPending = true
    private var currentHost: GlassHostLayout? = null
    private var animationFrameRate: Float? = null
    private var frameRateReset: Runnable? = null

    private fun requestAnimationFrameRate(animating: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 35) return
        // Home owns its own native/window cadence. Raising this View's vote
        // promotes the entire busy feed, not just our small optical Surface.
        if (animating && (navigationVisibility.selected() ?: selectedIndex) != 0) {
            if (animationFrameRate == null) {
                animationFrameRate = glassHost.requestedFrameRate
                // The native feed can have its own high-rate Surface while the
                // transparent navigation host otherwise keeps a 60 Hz vote.
                glassHost.requestedFrameRate = glassHost.display?.supportedModes
                    ?.maxOfOrNull { it.refreshRate }
                    ?: View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            }
        } else {
            frameRateReset?.let(glassHost::removeCallbacks)
            frameRateReset = null
            animationFrameRate?.let {
                glassHost.requestedFrameRate = it
                animationFrameRate = null
            }
        }
    }
    private var cartOpticsBase: io.github.offlineglass.config.GlassConfig? = null
    private var cartOpticsApplied: io.github.offlineglass.config.GlassConfig? = null
    private data class BlurInput(val backdrop: Bitmap?,
        val generation: Long, val width: Int, val height: Int,
        val effect: android.graphics.RenderEffect?, val x: Int, val y: Int)
    private val backdropGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var unchangedRootCopies = 0
    @Volatile private var contentInteractionUntil = 0L
    private var lastRootCopyAt = 0L
    private val touchLocation = IntArray(2)
    private var recordedBlur: BlurInput? = null
    private var pendingBlur: BlurInput? = null
    private val blurLocation = IntArray(2)
    private data class SceneInput(val backdrop: Bitmap?,
        val generation: Long, val route: Long, val width: Int, val height: Int,
        val x: Int, val y: Int, val config: io.github.offlineglass.config.GlassConfig)
    private data class IndicatorInput(val scene: SceneInput, val navigation: Bitmap?,
        val signature: Long, val dark: Boolean, val selected: Int)
    private var outerInput: SceneInput? = null
    private var pendingOuterInput: SceneInput? = null
    private var indicatorInput: IndicatorInput? = null
    private var pendingIndicatorInput: IndicatorInput? = null

    private fun sceneInput(backdrop: Bitmap?, width: Int, height: Int): SceneInput {
        glassHost.getLocationInWindow(blurLocation)
        return SceneInput(backdrop, backdropGeneration.get(), captureRouteEpoch,
            width, height, blurLocation[0], blurLocation[1], glassHost.config)
    }

    override fun reuseOuterBackdropForSurface(surfaceActive: Boolean): Boolean = surfaceActive
    override fun outerBackdropNeedsRecord(backdrop: Bitmap?, width: Int, height: Int,
        hasDisplayList: Boolean): Boolean {
        val key = sceneInput(backdrop, width, height)
        pendingOuterInput = key
        return !hasDisplayList || outerInput != key
    }
    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerInput = pendingOuterInput
    }
    override fun reuseCombinedIndicatorScene(surfaceActive: Boolean): Boolean = surfaceActive
    override val retainsIndicatorReturnLayers = true
    override fun combinedIndicatorSceneNeedsRecord(backdrop: Bitmap?, navigation: Bitmap?,
        signature: Long, width: Int, height: Int, dark: Boolean, hasDisplayList: Boolean): Boolean {
        val key = IndicatorInput(sceneInput(backdrop, width, height), navigation, signature,
            dark, glassHost.adapterSelectedIndex)
        pendingIndicatorInput = key
        return !hasDisplayList || indicatorInput != key
    }
    override fun onCombinedIndicatorSceneRecorded(backdrop: Bitmap?, navigation: Bitmap?,
        signature: Long, width: Int, height: Int, dark: Boolean) {
        indicatorInput = pendingIndicatorInput
    }

    override fun onOpticalBackdropChanged() {
        unchangedRootCopies = 0
        backdropGeneration.incrementAndGet()
    }

    override fun onOpticalBackdropCaptured(barSource: android.graphics.Rect?) {
        unchangedRootCopies = (unchangedRootCopies + 1).coerceAtMost(1000)
    }

    override fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit): Boolean {
        // Home's root-window readback already runs after its native traversal.
        // An extra frame-commit hop plus copy/render completion serializes it
        // to alternating frames. Preserve the original direct request path.
        if ((navigationVisibility.selected() ?: selectedIndex) == 0) return false
        val now = SystemClock.uptimeMillis()
        if (now >= contentInteractionUntil && unchangedRootCopies >= 12) {
            val period = 1000f / (root.display?.refreshRate?.takeIf { it > 0f } ?: 60f)
            val stride = if (unchangedRootCopies >= 36) 8 else 4
            if (now - lastRootCopyAt < period * stride) return true
        }
        return frameCopyScheduler.schedule(root) {
            lastRootCopyAt = SystemClock.uptimeMillis()
            copy()
        }
    }

    override fun opticalCaptureEpoch(): Long = captureRouteEpoch
    @Volatile private var captureRouteEpoch = 0L

    override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: Bitmap?, width: Int,
        height: Int, effect: android.graphics.RenderEffect?): Boolean {
        currentHost?.getLocationInWindow(blurLocation)
        val input = BlurInput(backdrop, backdropGeneration.get(), width, height,
            effect, blurLocation[0], blurLocation[1])
        pendingBlur = input
        return !prepared || recordedBlur != input
    }

    override fun onOpticalBlurNodeRecorded(backdrop: Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?) {
        // Cache the key read BEFORE recording. A concurrent source publication
        // must invalidate the next frame, never label old pixels as new.
        recordedBlur = pendingBlur
    }
    private data class TouchableState(val clickable: Boolean, val focusable: Boolean)
    private val touchableStates = java.util.WeakHashMap<View, TouchableState>()

    private val edgeGlowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.TRANSPARENT
        style = android.graphics.Paint.Style.FILL
    }
    private var edgeGlowDensity=Float.NaN
    override fun drawPanelEdgeGlow(canvas: Canvas, path: android.graphics.Path, density: Float) {
        if (currentHost?.adapterContentOrSystemDark() != true) return
        if(edgeGlowDensity!=density){
            edgeGlowPaint.setShadowLayer(18f * density, 0f, 0f,
                android.graphics.Color.argb(14, 220, 235, 255))
            edgeGlowDensity=density
        }
        val saved = canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, edgeGlowPaint)
        canvas.restoreToCount(saved)
    }

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

    private var diagnosticTapGeneration = 0

    private fun observePageCommit(host: View, index: Int, previous: View?) {
        val generation = ++diagnosticTapGeneration
        val observer = host.viewTreeObserver
        val deadline = SystemClock.uptimeMillis() + 1800L
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (generation != diagnosticTapGeneration || SystemClock.uptimeMillis() >= deadline) {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    return true
                }
                val page = if (navigationVisibility.selected() == index) navigationVisibility.page() else null
                if (page != null && page !== previous && page.isHardwareAccelerated) {
                    observer.removeOnPreDrawListener(this)
                    observer.registerFrameCommitCallback {
                        if (generation == diagnosticTapGeneration && navigationVisibility.selected() == index &&
                            navigationVisibility.page() === page && android.os.Trace.isEnabled()) {
                            android.os.Trace.beginSection("LiquidTab.Taobao.PageRootCommit.$index")
                            android.os.Trace.endSection()
                        }
                    }
                }
                return true
            }
        }
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(listener)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }, 1800L)
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        navigationVisibility.bind(source)
        val observe = io.github.offlineglass.BuildConfig.DEBUG && android.os.Trace.isEnabled() &&
            navigationVisibility.selected()?.let { it != index } == true
        val previous = if (observe) navigationVisibility.page() else null
        val dispatched = tabTransitions.dispatch(source, index, slotCount) {
            TaobaoNativePageTransactions.click(navigationVisibility.nativeRouter()) {
                nativeTap(source, index, slotCount)
            }
        }
        if (observe && dispatched) observePageCommit(host, index, previous)
        return dispatched
    }

    private fun nativeTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
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
        navigationVisibility.bind(source)
        if (host is GlassHostLayout && badge.update(host, source)) surfaceRedrawPending = true
        selectedIndex = navigationVisibility.selected() ?: (host as? GlassHostLayout)?.adapterSelectedIndex ?: selectedIndex
        probeScene(host)
        flashSale = scene.flashSaleChannelActive
        if (selectedIndex == 0) traced("LiquidTab.Taobao.coupon") { pageChrome.adjustCoupon(host, source, flashSale) }
        traced("LiquidTab.Taobao.flashControls") { pageChrome.adjustFlashSaleControls(host, source, selectedIndex == 0 && flashSale) }
        traced("LiquidTab.Taobao.cartControls") { pageChrome.adjustCart(host, selectedIndex) }
        traced("LiquidTab.Taobao.messageInsets") { messageInsets.update(host, selectedIndex) }
    }

    private inline fun <T> traced(name: String, action: () -> T): T {
        android.os.Trace.beginSection(name)
        try { return action() } finally { android.os.Trace.endSection() }
    }

    fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            host.getLocationOnScreen(touchLocation)
            // Bottom-bar gestures animate optics, not the underlying feed.
            if (event.rawY < touchLocation[1]) noteContentInteraction()
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
        selectedIndex = navigationVisibility.selected() ?: selected()
        navigationVisibility.bind(source)
    }

    override fun onHostFrame(host: View) {
        currentHost = host as? GlassHostLayout
        probeScene(host)
        (host as? GlassHostLayout)?.let(::updateBackdropRendererMode)

    }

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        // Cart may tune the liquid backdrop, but cannot change material mode.
        if (host.adapterSelectedIndex == 3 && host.config.liquidGlassEnabled && !host.config.solidBarEnabled) {
            if (host.config !== cartOpticsApplied) cartOpticsBase = host.config
            val base = cartOpticsBase ?: host.config
            val wanted = cartOpticsApplied?.takeIf { host.config === it } ?:
                base.copy(blurRadius = maxOf(2f, base.blurRadius), backdropCapture = true)
            if (host.config != wanted) surfaceRedrawPending = true
            cartOpticsApplied = wanted
            host.config = wanted
        } else {
            if (host.config === cartOpticsApplied) cartOpticsBase?.let { host.config = it }
            cartOpticsBase = null
            cartOpticsApplied = null
        }
        // All tabs share one output; no teardown/recreation between My and Cart.
        // Sampling native Views from the worker must never re-enter their recording.
        val enabled = !cartPageActive() && host.adapterSelectedIndex in 0..4 && host.config.backdropCapture &&
            !host.config.solidBarEnabled && (host.config.liquidGlassEnabled || host.config.blurRadius > 0f)
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

    private var cartScene = java.lang.ref.WeakReference<View>(null)
    private var cartFramePending = false
    override fun forceLiveBackdropFrame(selectedIndex: Int): Boolean {
        if (!cartPageActive() || !cartFramePending) return false
        cartFramePending = false
        return true
    }
    fun bindCartScene(root: View) {
        if (cartScene.get() === root) return
        cartScene = java.lang.ref.WeakReference(root)
        val state = java.lang.ref.WeakReference(this)
        TaobaoCartRenderSurface.attach(root) {
            state.get()?.let {
                if (it.cartPageActive()) {
                    it.cartFramePending = true
                    it.glassHost.invalidate()
                    TaobaoCartNativeBridge.onNativeContentFrame()
                }
            }
        }
    }
    override fun resolveBackdropScene(host: View): View? {
        if (!cartPageActive()) return null
        val root = cartScene.get()?.takeIf { it.isAttachedToWindow && it.isShown } ?: return null
        var ancestor: View? = host
        while (ancestor != null) {
            if (ancestor === root) return null
            ancestor = ancestor.parent as? View
        }
        return root
    }
    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = resolveBackdropScene(glassHost) == null

    override fun requiresSampledBackdrop(): Boolean = currentHost?.config?.let {
        it.enabled && it.backdropCapture && !it.solidBarEnabled &&
            (it.liquidGlassEnabled || it.blurRadius > 0f)
    } == true

    @Synchronized override fun opticalSurfaceCopyHandler(): Handler {
        opticalHandler?.let { return it }
        return HandlerThread("Taobao-Optical").let { thread ->
            thread.start()
            opticalThread = thread
            Handler(thread.looper).also { opticalHandler = it }
        }
    }

    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? {
        requestAnimationFrameRate(animating || SystemClock.uptimeMillis() < contentInteractionUntil)
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
    override fun onContentScroll() {
        noteContentInteraction()
    }

    private fun noteContentInteraction() {
        contentInteractionUntil = SystemClock.uptimeMillis() + 200L
        requestAnimationFrameRate(true)
        if (frameRateReset == null) {
            frameRateReset = object : Runnable {
                override fun run() {
                    val remaining = contentInteractionUntil - SystemClock.uptimeMillis()
                    if (remaining > 0L) glassHost.postDelayed(this, remaining)
                    else {
                        frameRateReset = null
                        if (!glassHost.adapterNavigationAnimationRunning) requestAnimationFrameRate(false)
                    }
                }
            }.also { glassHost.postDelayed(it, 200L) }
        }
        unchangedRootCopies = 0
    }

    private var sampledRoute = -1
    private var sceneFrameTime=Long.MIN_VALUE
    private var sceneWindow: View?=null
    private fun probeScene(host: View) {
        selectedIndex = navigationVisibility.selected() ?: (host as? GlassHostLayout)?.adapterSelectedIndex ?: selectedIndex
        val routeChanged = sampledRoute != selectedIndex
        val frameTime=host.drawingTime
        if(!routeChanged&&sceneWindow===host.rootView&&sceneFrameTime==frameTime)return
        sceneFrameTime=frameTime;sceneWindow=host.rootView
        if (routeChanged) {
            unchangedRootCopies = 0
            lastRootCopyAt = 0L
            contentInteractionUntil = SystemClock.uptimeMillis() + 200L
            captureRouteEpoch++
            frameCopyScheduler.cancel()
            surfaceRedrawPending = true
        }
        sampledRoute = selectedIndex
        // TBFragmentTabHost owns the current fragment. Scope optional chrome and
        // Surface discovery to its real, attached view, not cached sibling pages.
        val page=navigationVisibility.page() as? ViewGroup
        val root = page ?: host.rootView as? ViewGroup ?: return
        val change = traced("LiquidTab.Taobao.channelProbe") {
            scene.probe(root, homeSelected = selectedIndex == 0)
        } ?: return
        if (!change.flashSaleChanged) return
        val glass = host as? GlassHostLayout ?: return
        if (change.flashSale) glass.adapterOnScrollStopHidePageEntered(3_000L)
        else {
            glass.adapterCancelScrollStopHide()
            glass.adapterOnScrollStopHidePageExited()
        }
    }

    // The cart's Unicorn scene can draw hardware-only bitmaps.
    override val allowsSoftwareBackdropCapture: Boolean
        get() = !cartPageActive()

    internal fun cartPageActive(): Boolean =
        (navigationVisibility.selected() ?: glassHost.adapterSelectedIndex) == 3
    internal fun cartButtonBlurRadius(host: GlassHostLayout): Float =
        (cartOpticsBase ?: host.config).blurRadius

    override fun acceptCapturedNavigation(source: ViewGroup?, target: Bitmap, selectedIndex: Int): Boolean =
        artwork.acceptCandidate(source, target, selectedIndex).also { accepted ->
            if (accepted) surfaceRedrawPending = true
        }

    override fun replaceCapturedVideoSlot(target: Bitmap) = artwork.replaceVideoSlot(target)

    override fun onNavigationSnapshotReady(snapshot: Bitmap, slotCount: Int) {
        // Copy at UI publication. Shared staging buffers can be reused by the
        // next capture; the worker must only read this independent bitmap.
        navigationPixels = snapshot.copy(Bitmap.Config.ARGB_8888, false)
        surfaceRedrawPending = true
    }

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true

    override fun inspectNavigationCaptureLabels(source: ViewGroup, hasSnapshot: Boolean): io.github.offlineglass.hook.adapters.NavigationCaptureLabels {
        if (source.childCount != 5) return io.github.offlineglass.hook.adapters.NavigationCaptureLabels(emptyList())
        val videoSlot = source.getChildAt(1)
        val matches = ArrayList<android.widget.TextView>()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += videoSlot to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is android.widget.TextView && view.text?.toString()?.trim() == "视频" && view.isShown) matches += view
            if (view is ViewGroup && depth < 6) for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
        }
        return io.github.offlineglass.hook.adapters.NavigationCaptureLabels(
            if (matches.size == 1) matches else emptyList(),
        )
    }

    override fun temporarilyHideNavigationLabels(labels: List<android.widget.TextView>, selectedIndex: Int): List<Pair<android.widget.TextView, Int>> =
        if (selectedIndex == 1) emptyList() else videoTabOverlay.hideVideoTabLabel(labels, selectedIndex)

    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) { drawSource(); return true }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        val pixels = navigationPixels?.takeUnless(Bitmap::isRecycled)
        val sourceWidth = pixels?.width ?: currentHost?.adapterNavigationSource?.width ?: frame.width
        val sourceHeight = pixels?.height ?: currentHost?.adapterNavigationSource?.height ?: frame.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val nativeSlotWidth = sourceWidth / frame.slotCount.coerceAtLeast(1).toFloat()
        val scale = minOf(frame.slotWidth / nativeSlotWidth, frame.height / sourceHeight.toFloat()).coerceAtLeast(.0001f)
        val dy = (frame.height - sourceHeight * scale) / 2f
        for ((visual, index) in frame.enabledIndices.withIndex()) {
            val left = 4f * frame.density + visual * frame.slotWidth
            val center = left + frame.slotWidth / 2f
            val saved = canvas.save()
            canvas.clipRect(left, 0f, left + frame.slotWidth, frame.height.toFloat())
            val fixed = true
            val artworkSelection = if (frame.hideSelectedForOptics) index
                else if (frame.navigationOpticalMix > 0f) -1 else frame.selectedIndex
            if (fixed && artwork.drawFixedSlot(canvas, index, artworkSelection, frame.darkGlass,
                    center, frame.height / 2f, minOf(nativeSlotWidth, sourceHeight.toFloat()) * scale * frame.extraScale,
                    iconOnly = frame.iconOnly)) {
                canvas.restoreToCount(saved)
                continue
            }
            if (pixels != null) {
                canvas.scale(frame.extraScale, frame.extraScale, center, frame.height / 2f)
                val offset = if (index == 0 && frame.selectedIndex == 0 && artwork.selectedHomeContentCenterY.isFinite())
                    frame.height / 2f - (dy + artwork.selectedHomeContentCenterY * scale) else 0f
                canvas.translate(center - (index + .5f) * nativeSlotWidth * scale, dy + offset)
                canvas.scale(scale, scale)
                canvas.drawBitmap(pixels, 0f, 0f, navigationPaint.get()!!)
            }
            canvas.restoreToCount(saved)
        }
    }


    override fun drawFixedNavigationSlot(canvas: Canvas, index: Int, selectedIndex: Int,
                                         dark: Boolean, centerX: Float, centerY: Float, boxSize: Float): Boolean =
        currentHost.let { host ->
            val iconOnly = host?.config?.let { it.bottomBarIconOnly || it.iconOnly } == true
            if (index == 0 && selectedIndex == 0 && !iconOnly) false
            else artwork.drawFixedSlot(canvas, index, selectedIndex, dark, centerX,
                if (iconOnly && host != null) host.height / 2f else centerY, boxSize, iconOnly)
        }

    override fun sourceContentOffsetY(index: Int, selectedIndex: Int, hostHeight: Int,
                                      transformDy: Float, transformScale: Float): Float? =
        if (index == 0 && selectedIndex == 0 && artwork.selectedHomeContentCenterY.isFinite())
            hostHeight / 2f - (transformDy + artwork.selectedHomeContentCenterY * transformScale)
        else 0f

    override fun drawAppBadgeOverlay(canvas: Canvas, host: View, contentScale: Float) {
        badge.draw(canvas, contentScale)
    }


    override fun retainBackdropDuringTransition(now: Long): Boolean = now < scene.barPriorityUntil

    override fun retainHomeBackdrop(selectedIndex: Int): Boolean = selectedIndex == 0

    override fun deferThemeDetection(now: Long): Boolean = now < scene.barPriorityUntil

    override fun needsContinuousHomeFrames(selectedIndex: Int): Boolean = selectedIndex == 0

    override fun pageRequiresScrollStopHide(selectedIndex: Int): Boolean =
        selectedIndex == 0 && scene.flashSaleChannelActive

    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean =
        navigationVisibility.retain(currentHost,
            if (pagePreview.target >= 0) navigationVisibility.selected() ?: selectedIndex else selectedIndex)

    override fun retainNavigationWithoutNativeSource(selectedIndex: Int): Boolean = false

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        navigationVisibility.bind(source)
        return navigationVisibility.selected()
    }

    override fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean =
        tabTransitions.pendingTarget < 0 || index == tabTransitions.pendingTarget

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) requestAnimationFrameRate(false)
        if (!hasFocus) { TaobaoCartNativeBridge.suspend(); tabTransitions.cancel(); pagePreview.clear(); frameCopyScheduler.cancel() }
    }

    override fun onHostFocusLost() {
        requestAnimationFrameRate(false)
        TaobaoCartNativeBridge.suspend()
        tabTransitions.cancel(); pagePreview.clear(); frameCopyScheduler.cancel()
    }

    override fun onBarGesture(phase: Int, host: View) {
        val now = SystemClock.uptimeMillis()
        scene.barPriorityUntil = now + if (phase == 2) 520L else 180L
        if (phase == 0 && scene.flashSaleChannelActive)
            (host as? GlassHostLayout)?.adapterCancelScrollStopHide()
        if (phase == 2) (host as? GlassHostLayout)?.adapterScheduleBackdropRefresh(536L)
    }

    override fun onHostDetached() {
        outerInput = null; pendingOuterInput = null
        indicatorInput = null; pendingIndicatorInput = null
        requestAnimationFrameRate(false)
        frameCopyScheduler.cancel()
        captureRouteEpoch++
        recordedBlur = null; pendingBlur = null
        tabTransitions.cancel()
        pagePreview.clear(dispose = true)
        currentHost = null
        sceneWindow=null;sceneFrameTime=Long.MIN_VALUE
        navigationPixels = null
        synchronized(this) {
            opticalThread?.quitSafely()
            opticalThread = null
            opticalHandler = null
        }
        surfaceRedrawPending = true
        artwork.dispose()
        scene.reset()
        pageChrome.dispose()
        messageInsets.dispose()
        badge.dispose()
        videoTabOverlay.dispose()
        navigationVisibility.clear()
        touchableStates.clear()
    }

}
