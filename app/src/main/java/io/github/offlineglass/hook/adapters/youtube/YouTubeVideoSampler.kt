package io.github.offlineglass.hook.adapters.youtube

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
internal class YouTubeVideoSampler {
    private var snapshots: List<AppVideoBackdrop> = emptyList()
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
    private var copyStarted = 0L
    private var lastCopyDuration = 0L
    @Volatile private var generation = 0
    private var inFlight = false
    private var epoch = 0
    private var captureApi: CaptureApi? = null
    private var captureListener: Any? = null
    private var layerCaptureFailed = false
    private var loggedFailure = false
    private val retired = ArrayDeque<List<AppVideoBackdrop>>()
    private var pendingFrames: MutableList<AppVideoBackdrop>? = null
    private val panelPosition = IntArray(2)

    fun frame(host: GlassHostLayout): AppVideoBackdrop? {
        if (snapshots.isEmpty()) return null
        host.getLocationInWindow(panelPosition)
        val density = host.resources.displayMetrics.density
        val padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(
            host.config.blurRadius * density, 24f * density).toInt() + 1
        val left = panelPosition[0] - padding
        val top = panelPosition[1] - padding
        val right = panelPosition[0] + host.width + padding
        val bottom = panelPosition[1] + host.height + padding
        var best: AppVideoBackdrop? = null
        var bestArea = 0L
        for (frame in snapshots) {
            val width = minOf(right, frame.surfaceLeft + frame.surfaceWidth) - maxOf(left, frame.surfaceLeft)
            val height = minOf(bottom, frame.surfaceTop + frame.surfaceHeight) - maxOf(top, frame.surfaceTop)
            val area = if (width > 0 && height > 0) width.toLong() * height else 0L
            if (area > bestArea) { best = frame; bestArea = area }
        }
        return best
    }

    fun request(host: GlassHostLayout, allowed: Boolean) {
        if (!allowed || !host.isAttachedToWindow) {
            clear()
            return
        }
        currentHost = host
        val now = SystemClock.uptimeMillis()
        if (now - lastScan >= 250L || (source != null && source?.isShown != true)) {
            lastScan = now
            val next = findVideo(host.rootView, host)
            if (next !== source) {
                clear()
                currentHost = host
                source = next
                layerCaptureFailed = false
            }
        }
        val video = source ?: return
        hasVideo = true
        if (inFlight && now - copyStarted > 500L) {
            generation++
            inFlight = false
            cancelPendingFrames()
        }
        if (host.alpha <= 0.001f || !host.isShown || !host.hasWindowFocus()) {
            main.removeCallbacks(wakeup)
            return
        }
        // Sampling at a fixed 32ms capped the optical video at ~31fps even
        // on a 120Hz display. Requests remain single-flight and vsync driven.
        val refresh = (host.display?.refreshRate?.takeIf { it.isFinite() && it > 0f }
            ?: 60f)
        // Follow the actual display rate and reserve elapsed time for app work.
        // Slow readbacks automatically back off instead of accumulating jobs.
        val interval = maxOf((1000f / refresh).toLong().coerceAtLeast(1L), lastCopyDuration * 4L)
        if (inFlight || now - lastCopy < interval) {
            main.removeCallbacks(wakeup)
            main.postDelayed(wakeup, if (inFlight) interval else (interval - (now - lastCopy)).coerceAtLeast(1L))
            return
        }
        lastCopy = now
        val location = IntArray(2).also(video::getLocationInWindow)
        val width = video.width
        val height = video.height
        if (width <= 0 || height <= 0) return
        val regions = ArrayList<YouTubeCaptureRegions.Region>(6)
        fun include(panel: GlassHostLayout) {
            val position = panelPosition.also(panel::getLocationInWindow)
            val density = panel.resources.displayMetrics.density
            val padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(
                panel.config.blurRadius * density, 24f * density).toInt() + 1
            val left = (position[0] - location[0] - padding).coerceAtLeast(0)
            val top = (position[1] - location[1] - padding).coerceAtLeast(0)
            val right = (position[0] - location[0] + panel.width + padding).coerceAtMost(width)
            val bottom = (position[1] - location[1] + panel.height + padding).coerceAtMost(height)
            if (right > left && bottom > top) regions += YouTubeCaptureRegions.Region(left, top, right, bottom)
        }
        include(host)
        if (regions.isEmpty()) {
            // A feed/mini-player video away from the glass cannot contribute
            // pixels. Avoid full TextureView buffers in that case.
            if (snapshots.isNotEmpty()) {
                retireSnapshot()
                host.postInvalidateOnAnimation()
            }
            main.removeCallbacks(wakeup)
            return
        }
        val ticket = ++generation
        copyStarted = now
        if (video is TextureView) {
            val frame = runCatching { video.getBitmap((width / 2).coerceAtLeast(1), (height / 2).coerceAtLeast(1)) }.getOrNull()
            complete(host, video, ticket, frame?.let {
                listOf(AppVideoBackdrop(it, location[0], location[1], width, height, 0))
            })
            return
        }
        val surface = video as? SurfaceView ?: return
        if (!surface.holder.surface.isValid) return
        inFlight = true
        captureRegions(host, surface, ticket, location, width, height,
            YouTubeCaptureRegions.plan(regions).map { Rect(it.left, it.top, it.right, it.bottom) })
    }

    private fun captureRegions(host: GlassHostLayout, video: SurfaceView, ticket: Int,
                               location: IntArray, width: Int, height: Int, crops: List<Rect>) {
        val frames = ArrayList<AppVideoBackdrop>(crops.size)
        pendingFrames = frames
        fun next(index: Int) {
            if (ticket != generation) {
                frames.forEach { it.bitmap.recycle() }; frames.clear(); return
            }
            if (index == crops.size) { complete(host, video, ticket, frames); return }
            val crop = crops[index]
            val result: (Bitmap?) -> Unit = { frame ->
                if (ticket != generation || frame == null) {
                    frames.forEach { it.bitmap.recycle() }
                    frames.clear()
                    frame?.recycle()
                    complete(host, video, ticket, null)
                } else {
                    frames += AppVideoBackdrop(frame, location[0] + crop.left, location[1] + crop.top,
                        crop.width(), crop.height(), 0)
                    next(index + 1)
                }
            }
            if (!layerCaptureFailed && Build.VERSION.SDK_INT >= 29) {
                captureLayer(video, ticket, crop) { frame ->
                    if (frame == null && ticket == generation) {
                        layerCaptureFailed = true
                        result(null)
                    } else result(frame)
                }
            } else result(null)
        }
        // At most two sequential regions, one batch in flight. Publish both
        // together so top and bottom never consume partially replaced input.
        next(0)
    }

    private fun captureLayer(video: SurfaceView, ticket: Int, crop: Rect, result: (Bitmap?) -> Unit) {
        // Capture the decoder's
        // own compositor layer, restricted to this process, as in the proven
        // Douyin path. Do not sample the window or duplicate its SurfaceView hole.
        val control = runCatching { video.surfaceControl }.getOrNull()
        if (control?.isValid != true) {
            result(null)
            return
        }
        copyHandler().post {
            if (ticket != generation) { main.post { result(null) }; return@post }
            var delivered = false // Accessed only by main-thread completion messages.
            fun deliver(frame: Bitmap?) {
                main.post {
                    if (delivered) frame?.recycle()
                    else { delivered = true; result(frame) }
                }
            }
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
                HiddenApiBypass.invoke(api.baseBuilder, builder, "setFrameScale", 1f)
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
                    deliver(frame)
                }
                val listener = HiddenApiBypass.newInstance(api.listener, consumer)
                captureListener = listener
                val status = HiddenApiBypass.invoke(api.capture, null, "captureLayers", args, listener) as Int
                if (status != 0) deliver(null)
            }.onFailure {
                deliver(null)
            }
        }
    }

    private fun complete(host: GlassHostLayout, video: View, ticket: Int, frames: List<AppVideoBackdrop>?) {
        if (ticket == generation) pendingFrames = null
        if (ticket != generation || source !== video || !host.isAttachedToWindow || !video.isShown) {
            frames?.forEach { it.bitmap.recycle() }
            return
        }
        inFlight = false
        captureListener = null
        if (frames == null) {
            if (!loggedFailure) {
                loggedFailure = true
                XposedBridge.log("[OfflineGlass][YouTubeVideo] decoder layer unavailable")
            }
            main.removeCallbacks(wakeup)
            main.postDelayed(wakeup, 250L)
            return
        }
        retireSnapshot()
        lastCopyDuration = (SystemClock.uptimeMillis() - copyStarted).coerceIn(0L, 500L)
        main.removeCallbacks(wakeup)
        val nextEpoch = ++epoch
        snapshots = frames.map { it.copy(epoch = nextEpoch) }
        host.postInvalidateOnAnimation()
    }

    private fun findVideo(root: View, host: View): View? {
        var best: View? = null
        var area = 0L
        var overlap = 0L
        val owned = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
        val panels = ArrayList<Rect>(6)
        fun include(panel: GlassHostLayout) {
            panel.opticalSurfaceViewForAdapter?.let { owned.add(it) }
            val bounds = Rect()
            if (!panel.isShown || panel.alpha <= .001f || !panel.getGlobalVisibleRect(bounds)) return
            val density = panel.resources.displayMetrics.density
            val padding = io.github.offlineglass.rendering.NativeBackdropBlur.padding(
                panel.config.blurRadius * density, 24f * density).toInt() + 1
            bounds.inset(-padding, -padding)
            panels += bounds
        }
        (host as? GlassHostLayout)?.let(::include)
        val bounds = Rect()
        var visited = 0
        val candidates = ArrayList<View>()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (++visited > 4096) return null
            if (view is GlassHostLayout) {
                view.opticalSurfaceViewForAdapter?.let { owned.add(it) }
                continue
            }
            if (view is AdapterOwnedOverlay || !view.isShown) continue
            if (view is SurfaceView || view is TextureView) candidates += view
            if (view is ViewGroup && depth < 40) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        for (view in candidates) {
            val size = view.width.toLong() * view.height
            if (view !in owned && size > 0 && view.getGlobalVisibleRect(bounds)) {
                // Source choice is about pixels under the glass, not which
                // decoder happens to have the largest off-screen View.
                val covered = panels.sumOf { panel ->
                    val w = (minOf(bounds.right, panel.right) - maxOf(bounds.left, panel.left)).coerceAtLeast(0)
                    val h = (minOf(bounds.bottom, panel.bottom) - maxOf(bounds.top, panel.top)).coerceAtLeast(0)
                    w.toLong() * h
                }
                if (covered > overlap || (covered > 0 && covered == overlap && size > area)) {
                    best = view; area = size; overlap = covered
                }
            }
        }
        return best
    }

    private fun copyHandler(): Handler = handler ?: HandlerThread("LiquidTab-YouTube-Video").let {
        it.start()
        worker = it
        Handler(it.looper).also { result -> handler = result }
    }

    private fun retireSnapshot() {
        if (snapshots.isNotEmpty()) retired.addLast(snapshots)
        snapshots = emptyList()
        // Keep recently recorded frames alive across the UI/render-thread boundary.
        while (retired.size > 3) retired.removeFirst().forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
    }

    private fun clear() {
        val visibleHost = currentHost?.takeIf { snapshots.isNotEmpty() }
        lastCopyDuration = 0L
        lastCopy = 0L
        main.removeCallbacks(wakeup)
        currentHost = null
        generation++
        cancelPendingFrames()
        inFlight = false
        source = null
        hasVideo = false
        captureListener = null
        retireSnapshot()
        visibleHost?.postInvalidateOnAnimation()
    }

    fun pause() {
        // Preserve the bitmap referenced by Recents/display lists, while
        // stopping readbacks, retries and worker activity.
        generation++
        cancelPendingFrames()
        inFlight = false
        captureListener = null
        main.removeCallbacks(wakeup)
        currentHost = null
        lastScan = 0L
        worker?.quitSafely()
        worker = null
        handler = null
    }

    /** A verified native tab change invalidates the old decoder dependency. */
    fun invalidatePage() {
        clear()
        lastScan = 0L
    }

    fun dispose() {
        clear()
        pause()
        while (retired.isNotEmpty()) retired.removeFirst().forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
    }

    private fun cancelPendingFrames() {
        pendingFrames?.let { frames ->
            frames.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            frames.clear()
        }
        pendingFrames = null
    }

    private data class CaptureApi(val capture: Class<*>, val builder: Class<*>,
                                  val baseBuilder: Class<*>, val listener: Class<*>)
}
