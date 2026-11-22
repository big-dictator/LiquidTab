package io.github.offlineglass.hook.adapters.jd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.RenderNode
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Five full-resolution, foreground-only page stills. Never cache business views. */
internal class JdPagePreview(private val host: GlassHostLayout) {
    private data class Frame(val bitmap: Bitmap, val x: Int, val y: Int, val bytes: Long)
    private val frames = LinkedHashMap<Int, Frame>(5, .75f, true)
    private var overlay: PreviewView? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val handoffHandler = Handler(android.os.Looper.getMainLooper())
    private var capturePending = false
    private var captureEpoch = 0
    private var capturedEpoch = -1
    private var observedTab = -1
    private var observedMotionUntil = 0L
    private var lastLivePage: java.lang.ref.WeakReference<ViewGroup>? = null
    private var captureSource: java.lang.ref.WeakReference<View>? = null
    private var deferredLayoutPage: ViewGroup? = null

    fun nativeWillDispatch() {
        deferredLayoutPage?.suppressLayout(false)
        deferredLayoutPage = null
    }
    private var eligibleAt = 0L
    private var geometry = ""
    private var captureWake: Runnable? = null
    private var wakeAt = 0L
    private val disk = JdPageDiskCache(java.io.File(host.context.cacheDir, "liquidtab-jd-pages-v1"))
    private var diskKey: String? = null
    private var loadPending = false
    private var saveJobs = 0
    private var loadEpoch = 0
    private var restoredFrames = 0
    private val dirtyDisk = mutableSetOf<Int>()
    private var diskWakeScheduled = false
    private var diskWakeAt = 0L
    private val saveDisk = Runnable { diskWakeScheduled = false; persistDirty() }
    private fun scheduleDiskSave(delay: Long = 30_000L) {
        val deadline = SystemClock.uptimeMillis() + delay
        if (!diskWakeScheduled || deadline < diskWakeAt) {
            host.removeCallbacks(saveDisk)
            diskWakeScheduled = true
            diskWakeAt = deadline
            host.postDelayed(saveDisk, delay)
        }
    }
    private val snapshot = runCatching {
        HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap", RenderNode::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    }.getOrNull()
    private var transition = 0
    private var commitObserver: android.view.ViewTreeObserver? = null
    private var commitCallback: Runnable? = null
    private var readyDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var dispatchedAt = 0L
    private var transitionStartedAt = 0L
    private var nativeStartDelayMs = -1L
    private var handoffMs = -1L
    private var handoffFallback = false
    private var animationCommittedAt = 0L
    private var nativeStartedAt = 0L
    private var nativeClickMs = -1L
    private var animationCommitMs = -1L
    private var dispatchWaitMs = -1L
    private var firstCommitMs = -1L
    private var commitQueueMs = -1L
    fun animationFrameCommitted() {
        animationCommittedAt = SystemClock.uptimeMillis()
        animationCommitMs = animationCommittedAt - transitionStartedAt
    }
    fun nativeStarting() {
        nativeStartedAt = SystemClock.uptimeMillis()
        dispatchWaitMs = if (animationCommittedAt > 0L) nativeStartedAt - animationCommittedAt else -1L
    }
    var target = -1
        private set
    var sceneRevision = 0
        private set
    val scene: View? get() = overlay?.takeIf { target >= 0 && it.visibility == View.VISIBLE }
    private val finishFallback = Runnable { handoffFallback = true; finish() }
    private val stopWorker = Runnable {
        if (!capturePending && !loadPending && saveJobs == 0) { worker?.quitSafely(); worker = null; workerHandler = null }
    }

    private fun background(): Handler {
        host.removeCallbacks(stopWorker)
        return workerHandler ?: HandlerThread("LiquidTab-JD-PageCache", android.os.Process.THREAD_PRIORITY_BACKGROUND)
            .also { it.start(); worker = it }.let { Handler(it.looper).also { workerHandler = it } }
    }

    private fun loadSaved() {
        if (!host.hasWindowFocus() || host.rootView.width <= 0 || host.rootView.height <= 0) return
        val key = "${host.rootView.width}:${host.rootView.height}:${host.isDarkGlass()}:${host.config.themeMode}"
        if (diskKey == key) return
        diskKey = key; loadPending = true
        val epoch = ++loadEpoch
        background().post {
            val saved = disk.load(key)
            host.post {
                loadPending = false
                if (epoch == loadEpoch && host.isAttachedToWindow && host.hasWindowFocus()) {
                    for (entry in saved) if (!frames.containsKey(entry.index)) {
                        val bytes = entry.bitmap.width.toLong() * entry.bitmap.height * 4L
                        if (frames.values.sumOf { it.bytes } + bytes <= MAX_BYTES)
                            frames[entry.index] = Frame(entry.bitmap, entry.x, entry.y, bytes)
                        if (frames.containsKey(entry.index)) restoredFrames++
                    }
                }
                host.postDelayed(stopWorker, 1000L)
            }
        }
    }

    private fun persistDirty(force: Boolean = false) {
        if (saveJobs > 0 && !force || dirtyDisk.isEmpty()) return
        if (!force && (target >= 0 || host.adapterNavigationAnimationRunning || capturePending ||
                SystemClock.uptimeMillis() < observedMotionUntil)) {
            scheduleDiskSave(250L)
            return
        }
        val key = diskKey ?: return
        val batch = dirtyDisk.mapNotNull { index -> frames[index]?.let {
            JdPageDiskCache.Entry(index, it.bitmap, it.x, it.y)
        } }
        dirtyDisk.clear(); saveJobs++
        background().post {
            for (entry in batch) disk.save(key, entry)
            host.post {
                saveJobs = (saveJobs - 1).coerceAtLeast(0)
                if (dirtyDisk.isNotEmpty()) scheduleDiskSave()
                host.postDelayed(stopWorker, 1000L)
            }
        }
    }

    private inner class PreviewView : View(host.context), AdapterOwnedOverlay {
        var frame: Frame? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val location = IntArray(2)
        init { setWillNotDraw(false); isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (host.isDarkGlass()) Color.rgb(32, 32, 32) else Color.WHITE)
            val value = frame ?: return
            getLocationInWindow(location)
            canvas.drawBitmap(value.bitmap, (value.x - location[0]).toFloat(), (value.y - location[1]).toFloat(), paint)
        }
        // A preview must never submit an action against stale product/price data.
        override fun onTouchEvent(event: MotionEvent): Boolean = true
        override fun toString(): String = super.toString() + " ${host.adapterOpticalStateForDiagnostics}" +
            " previewTarget=$target blank=${frame == null} cachedTabs=${frames.keys} cacheBytes=${frames.values.sumOf { it.bytes }} restored=$restoredFrames nativeStartDelayMs=$nativeStartDelayMs handoffMs=$handoffMs handoffFallback=$handoffFallback animationCommitMs=$animationCommitMs dispatchWaitMs=$dispatchWaitMs nativeClickMs=$nativeClickMs firstCommitMs=$firstCommitMs commitQueueMs=$commitQueueMs ${disk.status} ${JdHiddenPageWork.status()}"
    }

    fun begin(index: Int): Boolean {
        // Encoding full-resolution PNGs during every second of foreground use
        // can monopolize a CPU core. Keep RAM fresh; checkpoint off the spring.
        scheduleDiskSave(1500L)
        captureEpoch++
        captureWake?.let(host::removeCallbacks); captureWake = null
        val frame = frames[index]
        val parent = host.parent as? FrameLayout ?: return false
        val view = overlay ?: PreviewView().also {
            overlay = it
            parent.addView(it, parent.indexOfChild(host).coerceAtLeast(0),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        target = index
        // Already queued React/RecyclerView work can request layout after a
        // second tap. While an opaque preview covers this page, defer only its
        // child layout; restore before the native transaction. No JS or shared
        // renderer is frozen, and visible animation is untouched.
        lastLivePage?.get()?.takeIf { it.isAttachedToWindow && !it.isLayoutSuppressed }?.let {
            it.suppressLayout(true)
            deferredLayoutPage = it
        }
        transitionStartedAt = SystemClock.uptimeMillis()
        animationCommittedAt = 0L; nativeStartedAt = 0L
        animationCommitMs = -1L; dispatchWaitMs = -1L; nativeClickMs = -1L
        firstCommitMs = -1L; commitQueueMs = -1L
        sceneRevision++
        view.frame = frame; view.visibility = View.VISIBLE; view.invalidate()
        host.clearLiveBackdropForAdapter()
        host.postInvalidateOnAnimation()
        return true
    }

    fun nativeDispatched() {
        if (target < 0) return
        val token = transition
        dispatchedAt = SystemClock.uptimeMillis()
        nativeClickMs = if (nativeStartedAt > 0L) dispatchedAt - nativeStartedAt else -1L
        nativeStartDelayMs = (dispatchedAt - transitionStartedAt).takeIf { transitionStartedAt > 0L } ?: -1L
        handoffFallback = false
        // The next traversal processes the native Fragment/layout changes.
        // Its first committed frame is sufficient: do not serially wait for
        // another host-only frame while the ready page is hidden by our still.
        commitObserver = host.rootView.viewTreeObserver
        readyDraw = android.view.ViewTreeObserver.OnPreDrawListener {
            readyDraw?.let { listener ->
                commitObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
            readyDraw = null
            if (token == transition && target >= 0 && host.isAttachedToWindow) {
                commitCallback = Runnable {
                    val committedAt = SystemClock.uptimeMillis()
                    // The target frame is already committed. Retiring our
                    // still is presentation-only and must not queue behind JD
                    // mount/JS follow-up messages (observed 82-148 ms). Never
                    // move page construction or business work to queue front.
                    handoffHandler.postAtFrontOfQueue {
                        if (token == transition) {
                            firstCommitMs = if (nativeStartedAt > 0L) committedAt - nativeStartedAt else -1L
                            commitQueueMs = SystemClock.uptimeMillis() - committedAt
                            finish()
                        }
                    }
                }
                commitObserver?.takeIf { it.isAlive }?.registerFrameCommitCallback(commitCallback!!)
            }
            true
        }
        commitObserver?.addOnPreDrawListener(readyDraw!!)
        host.rootView.postInvalidateOnAnimation()
        host.postDelayed(finishFallback, 800L)
    }

    fun finish() {
        nativeWillDispatch()
        if (target >= 0 && dispatchedAt > 0L) handoffMs = SystemClock.uptimeMillis() - dispatchedAt
        dispatchedAt = 0L
        transition++
        target = -1
        sceneRevision++
        host.removeCallbacks(finishFallback)
        readyDraw?.let { listener ->
            commitObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        readyDraw = null
        commitCallback?.let { callback ->
            commitObserver?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
        }
        commitObserver = null; commitCallback = null
        overlay?.let { it.visibility = View.GONE; it.frame = null }
        eligibleAt = SystemClock.uptimeMillis() + CACHE_INTERVAL_MS
        host.clearLiveBackdropForAdapter()
        host.postInvalidateOnAnimation()
    }

    fun maybeCapture(index: Int, page: View?, motionUntil: Long) {
        // Async completions must never turn a departed page back into the
        // current source. Only the live host frame may establish this binding.
        if (index != host.navigationIndex) return
        if (captureSource?.get() !== page) {
            captureSource = page?.let { java.lang.ref.WeakReference(it) }
            captureEpoch++
            capturedEpoch = -1
        }
        if (target < 0 && page is ViewGroup && page !== overlay && page.isShown)
            lastLivePage = java.lang.ref.WeakReference(page)
        loadSaved()
        val now = SystemClock.uptimeMillis()
        if (observedTab != index) {
            observedTab = index
            observedMotionUntil = motionUntil
            eligibleAt = maxOf(now, motionUntil) + CACHE_INTERVAL_MS
            captureEpoch++
            capturedEpoch = -1
        } else if (motionUntil > observedMotionUntil) {
            // Coalesce a scroll burst into one fresh still after motion ends.
            // Treating 50 ms as a continuous full-screen capture cadence kept
            // UI/GPU work alive at ~20 Hz and competed with every tab spring.
            observedMotionUntil = motionUntil
            eligibleAt = motionUntil + CACHE_INTERVAL_MS
            captureEpoch++
            capturedEpoch = -1
        }
        if (snapshot == null || target >= 0 || capturePending || capturedEpoch == captureEpoch ||
            page == null || page === overlay || !host.hasWindowFocus() || !page.isShown) return
        val readyAt = eligibleAt
        if (now < readyAt || host.adapterNavigationAnimationRunning) {
            val retryAt = if (now < readyAt) readyAt else now + 32L
            if (captureWake == null || wakeAt != retryAt) {
                captureWake?.let(host::removeCallbacks)
                val epoch = captureEpoch
                wakeAt = retryAt
                captureWake = Runnable {
                    captureWake = null
                    if (epoch == captureEpoch && captureSource?.get() === page &&
                        index == host.navigationIndex) maybeCapture(index, page, motionUntil)
                }.also { host.postDelayed(it, retryAt - now) }
            }
            return
        }
        val key = "${host.rootView.width}:${host.rootView.height}:${host.isDarkGlass()}"
        if (geometry.isNotEmpty() && geometry != key) frames.clear()
        geometry = key
        val bytes = page.width.toLong() * page.height * 4L
        if (bytes <= 0 || bytes > MAX_BYTES) return
        val node = runCatching { XposedHelpers.callMethod(page, "updateDisplayListIfDirty") as? RenderNode }
            .getOrNull() ?: return
        val position = IntArray(2).also(page::getLocationInWindow)
        val frozenInput = RenderNode("JdPagePreviewInput")
        frozenInput.setPosition(0, 0, page.width, page.height)
        val canvas = frozenInput.beginRecording(page.width, page.height)
        canvas.drawColor(if (page.resources.configuration.uiMode and 0x30 == 0x20) Color.BLACK else Color.WHITE)
        canvas.translate(-page.left.toFloat(), -page.top.toFloat())
        canvas.drawRenderNode(node)
        // The cart checkout lives outside the native page. Include its glass
        // in the still, but never include the moving bottom navigation itself.
        if (index == 3) (host.parent as? ViewGroup)?.let { parent ->
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child is JdCartLiquidButtons && child.isShown) runCatching {
                    val childNode = XposedHelpers.callMethod(child, "updateDisplayListIfDirty") as RenderNode
                    val location = IntArray(2).also(child::getLocationInWindow)
                    canvas.save()
                    canvas.translate((location[0] - position[0] + page.left - child.left).toFloat(),
                        (location[1] - position[1] + page.top - child.top).toFloat())
                    canvas.drawRenderNode(childNode); canvas.restore()
                }
            }
        }
        frozenInput.endRecording()
        val epoch = captureEpoch
        capturePending = true
        eligibleAt = now + CACHE_INTERVAL_MS
        host.removeCallbacks(stopWorker)
        val handler = background()
        handler.post {
            val bitmap = runCatching {
                snapshot?.invoke(null, frozenInput, frozenInput.width, frozenInput.height) as? Bitmap
            }.getOrNull()
            host.post {
                capturePending = false
                if (bitmap != null && epoch == captureEpoch && observedTab == index &&
                    index == host.navigationIndex && captureSource?.get() === page &&
                    host.isAttachedToWindow && host.hasWindowFocus()) {
                    capturedEpoch = epoch
                    frames.remove(index)
                    while (frames.isNotEmpty() && (frames.size >= 5 || frames.values.sumOf { it.bytes } + bytes > MAX_BYTES))
                        frames.remove(frames.keys.first())
                    frames[index] = Frame(bitmap, position[0], position[1], bytes)
                    dirtyDisk.add(index)
                    scheduleDiskSave()
                } else if (bitmap == null) {
                    eligibleAt = SystemClock.uptimeMillis() + 5000L
                }
                host.postDelayed(stopWorker, 1000L)
                // A new tab or scroll burst increments captureEpoch and will
                // schedule its own single refresh. Never form an idle loop.
                // Ask the live host to supply its current page on the next
                // frame; never recursively resubmit this callback's old page.
                if (epoch != captureEpoch && host.isAttachedToWindow && host.hasWindowFocus())
                    host.postInvalidateOnAnimation()
            }
        }
    }

    fun clear(dispose: Boolean = false) {
        host.removeCallbacks(saveDisk); diskWakeScheduled = false
        persistDirty(force = true)
        loadEpoch++; diskKey = null
        captureEpoch++
        captureWake?.let(host::removeCallbacks); captureWake = null
        finish(); frames.clear(); geometry = ""; observedTab = -1; observedMotionUntil = 0L; capturedEpoch = -1
        host.removeCallbacks(stopWorker)
        if (dispose) {
            worker?.quitSafely(); worker = null; workerHandler = null
        } else host.postDelayed(stopWorker, 1000L)
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }; overlay = null
        lastLivePage = null
        captureSource = null
    }

    private companion object {
        const val CACHE_INTERVAL_MS = 50L
        const val MAX_BYTES = 64L * 1024L * 1024L
    }
}
