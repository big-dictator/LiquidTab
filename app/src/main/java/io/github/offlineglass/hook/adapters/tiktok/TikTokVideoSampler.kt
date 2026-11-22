package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.function.ObjIntConsumer

/** Copies the decoder layer by value; never draws/reparents the native video View. */
internal class TikTokVideoSampler(private val fullWidthBand: Boolean = false, private val onFrame: (() -> Unit)? = null) {
    var snapshot: AppVideoBackdrop? = null
        private set
    var hasVideo = false
        private set
    private val main = Handler(Looper.getMainLooper())
    private var currentHost: GlassHostLayout? = null
    private val wakeup = Runnable {
        currentHost?.takeIf { it.isAttachedToWindow && it.hasWindowFocus() && it.isShown && it.alpha > 0.001f }
            ?.postInvalidateOnAnimation()
    }
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private var source: View? = null
    private var lastScan = 0L
    private var lastCopy = 0L
    private var transitionStarted = 0L
    private var producerFrame = Long.MIN_VALUE
    private var frameChanges = 0
    private var frameClockFailed = false
    private var capturedProducerFrame = Long.MIN_VALUE
    private var copyStarted = 0L
    private var generation = 0
    private var inFlight = false
    private var epoch = 0
    private var captureApi: CaptureApi? = null
    private var captureListener: Any? = null
    private var layerCaptureFailed = false
    private var loggedFailure = false
    private val retired = ArrayDeque<Bitmap>()
    private companion object {
        // Keep the backdrop responsive while avoiding a 30 Hz decoder-layer
        // readback and the associated bitmap churn during continuous playback.
        const val MIN_COPY_INTERVAL_MS = 16L
        const val COPY_TIMEOUT_MS = 500L
    }

    fun snapshot() = snapshot
    fun awaitingFirstFrame(now: Long): Boolean = source is SurfaceView && snapshot == null &&
        transitionStarted != 0L && now-transitionStarted < COPY_TIMEOUT_MS
    fun request(view: View, allowed: Boolean) {
        val host = view as? GlassHostLayout ?: return
        if (!allowed || !host.isAttachedToWindow) {
            clear()
            return
        }
        currentHost = host
        val now = SystemClock.uptimeMillis()
        val previous = source
        val sourceStillOverlaps = previous == null || Rect().let { bounds ->
            val xy = IntArray(2).also(host::getLocationOnScreen)
            previous.isShown && previous.getGlobalVisibleRect(bounds) &&
                bounds.intersect(xy[0], xy[1], xy[0]+host.width, xy[1]+host.height)
        }
        if (now - lastScan >= 250L || !sourceStillOverlaps) {
            lastScan = now
            val next = findVideo(host.rootView, host)
            if (next !== source) {
                // Invalidate the old asynchronous ticket, but retain the last
                // valid backdrop until the new decoder supplies a frame.
                generation++
                inFlight = false
                captureListener = null
                transitionStarted = now
                lastCopy = 0L
                source = next
                producerFrame = Long.MIN_VALUE
                capturedProducerFrame = Long.MIN_VALUE
                frameChanges = 0
                frameClockFailed = false
                layerCaptureFailed = false
            }
        }
        val video = source ?: run {
            hasVideo = false
            if (snapshot != null && now-transitionStarted < 800L) {
                main.removeCallbacks(wakeup)
                main.postDelayed(wakeup, 32L)
            } else if (snapshot != null) {
                // A prior Surface snapshot is a temporary bridge, never the
                // owner of a photo page. Otherwise needsSurfaceBackdrop stays
                // true forever and prevents native image RenderNodes drawing.
                retireSnapshot()
                host.clearLiveBackdropForAdapter()
                host.postInvalidateOnAnimation()
            }
            return
        }
        if (video is TextureView) {
            hasVideo = false
            if (snapshot != null) {
                retireSnapshot()
                host.clearLiveBackdropForAdapter()
                host.postInvalidateOnAnimation()
            }
            val firstObservation = !TikTokTextureHooks.registered(video)
            TikTokTextureHooks.observe(video)
            if (firstObservation) host.postInvalidateOnAnimation()
            return
        }
        hasVideo = true
        if (inFlight && now - copyStarted > COPY_TIMEOUT_MS) {
            generation++
            inFlight = false
        }
        if (host.alpha <= 0.001f || !host.isShown || !host.hasWindowFocus()) {
            main.removeCallbacks(wakeup)
            return
        }
        if (inFlight) {
            main.removeCallbacks(wakeup)
            // A successful/failed copy invalidates the host on completion.
            // Poll only at the timeout boundary if a vendor callback is lost.
            main.postDelayed(wakeup, (COPY_TIMEOUT_MS - (now - copyStarted)).coerceAtLeast(1L))
            return
        }
        if (video is SurfaceView && !frameClockFailed) {
            val number = runCatching {
                (HiddenApiBypass.invoke(android.view.Surface::class.java, video.holder.surface,
                    "getNextFrameNumber") as Number).toLong()
            }.getOrNull()
            if (number == null || number <= 0) frameClockFailed = true
            else {
                if (producerFrame != Long.MIN_VALUE && number > producerFrame) frameChanges++
                if (number < producerFrame) { frameChanges = 0; capturedProducerFrame = Long.MIN_VALUE }
                producerFrame = number
                // Require observed frame advancement before using the hidden
                // capability as a clock. A constant vendor value never gates capture.
                // A vendor queue clock is a cadence hint, not proof that a
                // copied bitmap remains valid after rapid native rebinds.
                // Periodically revalidate instead of caching a blank forever.
                if (frameChanges >= 2 && number == capturedProducerFrame && snapshot != null &&
                    now-lastCopy < COPY_TIMEOUT_MS) {
                    main.removeCallbacks(wakeup)
                    main.postDelayed(wakeup, 16L)
                    return
                }
            }
        }
        if (now - lastCopy < MIN_COPY_INTERVAL_MS) {
            main.removeCallbacks(wakeup)
            main.postDelayed(wakeup, (MIN_COPY_INTERVAL_MS - (now - lastCopy)).coerceAtLeast(1L))
            return
        }
        lastCopy = now
        capturedProducerFrame = producerFrame
        val location = IntArray(2).also(video::getLocationInWindow)
        val width = video.width
        val height = video.height
        if (width <= 0 || height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val density = host.resources.displayMetrics.density
        val padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(
            host.config.blurRadius * density, 24f * density).toInt() + 1
        if (location[0] + width <= hostLocation[0] - padding ||
            location[0] >= hostLocation[0] + host.width + padding ||
            location[1] + height <= hostLocation[1] - padding ||
            location[1] >= hostLocation[1] + host.height + padding) {
            // A feed/mini-player video away from the glass cannot contribute
            // pixels. Avoid full TextureView buffers in that case.
            // Retain the valid band during a swipe's decoder gap. Leaving the
            // adapted page or detaching still clears it through request/clear.
            main.removeCallbacks(wakeup)
            return
        }
        val ticket = ++generation
        val surface = video as? SurfaceView ?: return
        if (!surface.holder.surface.isValid) return
        inFlight = true
        copyStarted = now
        if (!layerCaptureFailed && Build.VERSION.SDK_INT >= 29) {
            captureLayer(host, surface, ticket, location, width, height)
        } else {
            complete(host, surface, ticket, null, location[0], location[1], width, height)
        }
    }

    private fun captureLayer(host: GlassHostLayout, video: SurfaceView, ticket: Int,
                             location: IntArray, width: Int, height: Int) {
        // Capture the decoder's
        // own compositor layer, restricted to this process, as in the proven
        // Douyin path. Do not sample the window or duplicate its SurfaceView hole.
        val control = runCatching { video.surfaceControl }.getOrNull()
        if (control?.isValid != true) {
            complete(host, video, ticket, null, location[0], location[1], width, height)
            return
        }
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val padding = (host.config.blurRadius * host.resources.displayMetrics.density * 3f + 24f).toInt()
        val crop = Rect(
            (if (fullWidthBand) -location[0] - padding else hostLocation[0] - location[0] - padding).coerceAtLeast(0),
            (hostLocation[1] - location[1] - padding).coerceAtLeast(0),
            (if (fullWidthBand) host.rootView.width-location[0]+padding else hostLocation[0] - location[0] + host.width + padding).coerceAtMost(width),
            (hostLocation[1] - location[1] + host.height + padding).coerceAtMost(height),
        )
        if (crop.isEmpty) {
            inFlight = false
            return
        }
        copyHandler().post {
            runCatching {
                val api = captureApi ?: run {
                    val cls = if (Build.VERSION.SDK_INT >= 34) {
                        runCatching { Class.forName("android.window.ScreenCaptureInternal") }
                            .getOrElse { Class.forName("android.window.ScreenCapture") }
                    } else SurfaceControl::class.java
                    CaptureApi(cls, Class.forName(cls.name + "\$LayerCaptureArgs\$Builder"),
                        Class.forName(cls.name + "\$CaptureArgs\$Builder"),
                        Class.forName(cls.name + "\$ScreenCaptureListener")).also { captureApi = it }
                }
                val builder = HiddenApiBypass.newInstance(api.builder, control)
                HiddenApiBypass.invoke(api.baseBuilder, builder, "setSourceCrop", crop)
                HiddenApiBypass.invoke(api.baseBuilder, builder, "setFrameScale", 1f/3f)
                HiddenApiBypass.invoke(api.baseBuilder, builder, "setUid", android.os.Process.myUid().toLong())
                val args = HiddenApiBypass.invoke(api.builder, builder, "build")
                val consumer = ObjIntConsumer<Any> { capture, status ->
                    val frame = if (status == 0 && capture != null) runCatching {
                        // The Android 17 internal result is a buffer wrapper;
                        // use the same conversion as the validated decoder path.
                        val buffer = capture.javaClass.getMethod("getHardwareBuffer")
                            .invoke(capture) as HardwareBuffer
                        try {
                            val colorSpace = capture.javaClass.getMethod("getColorSpace")
                                .invoke(capture) as? ColorSpace
                            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                ?: error("wrapHardwareBuffer returned null")
                        } finally {
                            buffer.close()
                        }
                    }.getOrNull() else null
                    main.post {
                        if (frame == null && ticket == generation) {
                            layerCaptureFailed = true
                            complete(host, video, ticket, null, location[0], location[1], width, height)
                        } else complete(host, video, ticket, frame,
                            location[0] + crop.left, location[1] + crop.top, crop.width(), crop.height())
                    }
                }
                val listener = HiddenApiBypass.newInstance(api.listener, consumer)
                captureListener = listener
                val status = HiddenApiBypass.invoke(api.capture, null, "captureLayers", args, listener) as Int
                check(status == 0) { "captureLayers status=$status" }
            }.onFailure {
                main.post {
                    if (ticket == generation) {
                        layerCaptureFailed = true
                        complete(host, video, ticket, null, location[0], location[1], width, height)
                    }
                }
            }
        }
    }

    private fun complete(host: GlassHostLayout, video: View, ticket: Int, frame: Bitmap?,
                         left: Int, top: Int, width: Int, height: Int) {
        if (ticket != generation || source !== video || !host.isAttachedToWindow || !video.isShown) {
            frame?.recycle()
            return
        }
        inFlight = false
        captureListener = null
        if (frame == null) {
            if (!loggedFailure) {
                loggedFailure = true
                XposedBridge.log("[OfflineGlass][TikTokVideo] decoder layer unavailable")
            }
            main.removeCallbacks(wakeup)
            main.postDelayed(wakeup, 250L)
            return
        }
        retireSnapshot()
        snapshot = AppVideoBackdrop(frame, left, top, width, height, ++epoch)
        onFrame?.invoke()
        host.postInvalidateOnAnimation()
    }

    private fun findVideo(root: View, host: View): View? {
        val hostPosition = IntArray(2).also(host::getLocationOnScreen)
        val hostBounds = Rect(hostPosition[0], hostPosition[1], hostPosition[0]+host.width, hostPosition[1]+host.height)
        var best: View? = null
        var area = 0L
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view === host || view is GlassHostLayout || view is AdapterOwnedOverlay || !view.isShown) continue
            val bounds = Rect()
            if ((view is SurfaceView || view is TextureView) && feedMedia(view) && view.width >= root.width * 0.5f &&
                view.height >= root.height * 0.2f &&
                view.getGlobalVisibleRect(bounds) && bounds.intersect(hostBounds)) {
                val overlap = bounds.width().toLong() * bounds.height()
                if (overlap > area) { best = view; area = overlap }
            }
            if (view is ViewGroup && depth < 40) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return best
    }
    private fun feedMedia(view: View): Boolean {
        var node: View? = view
        for (depth in 0..16) {
            val current = node ?: break
            if (TikTokPlayerLayout.owns(current) ||
                io.github.offlineglass.hook.adapters.AdapterNavigationSearch.resourceEntryName(current) == "player_view") return true
            if (current is ViewGroup &&
                io.github.offlineglass.hook.adapters.AdapterNavigationSearch.resourceEntryName(current) == "view_rootview") {
                val nickname = current.resources.getIdentifier("tv_live_nickname", "id", current.context.packageName)
                val tips = current.resources.getIdentifier("tv_live_tips", "id", current.context.packageName)
                if (nickname != 0 && tips != 0 && current.findViewById<View>(nickname) != null &&
                    current.findViewById<View>(tips) != null) return true
            }
            node = current.parent as? View
        }
        return false
    }

    private fun copyHandler(): Handler = handler ?: HandlerThread("LiquidTab-TikTok-Video").let {
        it.start()
        worker = it
        Handler(it.looper).also { result -> handler = result }
    }

    private fun retireSnapshot() {
        snapshot?.bitmap?.let(retired::addLast)
        snapshot = null
        // Keep recently recorded frames alive across the UI/render-thread boundary.
        while (retired.size > 3) retired.removeFirst()
    }

    private fun clear() {
        main.removeCallbacks(wakeup)
        currentHost = null
        generation++
        inFlight = false
        source = null
        hasVideo = false
        captureListener = null
        retireSnapshot()
    }

    fun dispose() {
        clear()
        lastScan = 0L
        worker?.quitSafely()
        worker = null
        handler = null
        retired.clear()
    }

    private data class CaptureApi(val capture: Class<*>, val builder: Class<*>,
                                  val baseBuilder: Class<*>, val listener: Class<*>)
}

