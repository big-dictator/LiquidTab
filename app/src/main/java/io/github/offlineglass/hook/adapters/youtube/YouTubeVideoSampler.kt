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
import android.view.PixelCopy
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.function.ObjIntConsumer

/** Copies the decoder layer by value; never draws/reparents the native video View. */
internal class YouTubeVideoSampler {
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
    private var copyStarted = 0L
    private var generation = 0
    private var inFlight = false
    private var epoch = 0
    private var captureApi: CaptureApi? = null
    private var captureListener: Any? = null
    private var layerCaptureFailed = false
    private var loggedFailure = false
    private val retired = ArrayDeque<Bitmap>()

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
        }
        if (host.alpha <= 0.001f || !host.isShown || !host.hasWindowFocus()) {
            main.removeCallbacks(wakeup)
            return
        }
        if (inFlight || now - lastCopy < 32L) {
            main.removeCallbacks(wakeup)
            main.postDelayed(wakeup, if (inFlight) 32L else (32L - (now - lastCopy)).coerceAtLeast(1L))
            return
        }
        lastCopy = now
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
            // pixels. Avoid full TextureView/PixelCopy buffers in that case.
            if (snapshot != null) {
                retireSnapshot()
                host.postInvalidateOnAnimation()
            }
            main.removeCallbacks(wakeup)
            return
        }
        val ticket = ++generation
        if (video is TextureView) {
            val frame = runCatching { video.getBitmap((width / 2).coerceAtLeast(1), (height / 2).coerceAtLeast(1)) }.getOrNull()
            complete(host, video, ticket, frame, location[0], location[1], width, height)
            return
        }
        val surface = video as? SurfaceView ?: return
        if (!surface.holder.surface.isValid) return
        inFlight = true
        copyStarted = now
        if (!layerCaptureFailed && Build.VERSION.SDK_INT >= 29) {
            captureLayer(host, surface, ticket, location, width, height)
        } else {
            copyBuffer(host, surface, ticket, location, width, height)
        }
    }

    private fun captureLayer(host: GlassHostLayout, video: SurfaceView, ticket: Int,
                             location: IntArray, width: Int, height: Int) {
        // The decoder's queue may not support PixelCopy on HyperOS. Capture its
        // own compositor layer, restricted to this process, as in the proven
        // Douyin path. Do not sample the window or duplicate its SurfaceView hole.
        val control = runCatching { video.surfaceControl }.getOrNull()
        if (control?.isValid != true) {
            copyBuffer(host, video, ticket, location, width, height)
            return
        }
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val padding = (host.config.blurRadius * host.resources.displayMetrics.density * 3f + 24f).toInt()
        val crop = Rect(
            (hostLocation[0] - location[0] - padding).coerceAtLeast(0),
            (hostLocation[1] - location[1] - padding).coerceAtLeast(0),
            (hostLocation[0] - location[0] + host.width + padding).coerceAtMost(width),
            (hostLocation[1] - location[1] + host.height + padding).coerceAtMost(height),
        )
        if (crop.isEmpty) {
            inFlight = false
            retireSnapshot()
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
                HiddenApiBypass.invoke(api.baseBuilder, builder, "setFrameScale", 0.5f)
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
                            copyBuffer(host, video, ticket, location, width, height)
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
                        copyBuffer(host, video, ticket, location, width, height)
                    }
                }
            }
        }
    }

    private fun copyBuffer(host: GlassHostLayout, video: SurfaceView, ticket: Int,
                           location: IntArray, width: Int, height: Int) {
        val frame = video.holder.surfaceFrame
        val target = Bitmap.createBitmap(((frame.width().takeIf { it > 0 } ?: width) / 2).coerceAtLeast(1),
            ((frame.height().takeIf { it > 0 } ?: height) / 2).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(video, null, target, { result ->
                if (result != PixelCopy.SUCCESS) target.recycle()
                complete(host, video, ticket, target.takeIf { result == PixelCopy.SUCCESS },
                    location[0], location[1], width, height)
            }, main)
        }.onFailure {
            target.recycle()
            complete(host, video, ticket, null, location[0], location[1], width, height)
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
                XposedBridge.log("[OfflineGlass][YouTubeVideo] decoder layer and PixelCopy unavailable")
            }
            main.removeCallbacks(wakeup)
            main.postDelayed(wakeup, 250L)
            return
        }
        retireSnapshot()
        snapshot = AppVideoBackdrop(frame, left, top, width, height, ++epoch)
        host.postInvalidateOnAnimation()
    }

    private fun findVideo(root: View, host: View): View? {
        var best: View? = null
        var area = 0L
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view === host || view is GlassHostLayout || view is AdapterOwnedOverlay || !view.isShown) continue
            val size = view.width.toLong() * view.height
            if ((view is SurfaceView || view is TextureView) && view.width >= root.width * 0.5f &&
                view.height >= root.height * 0.2f && size > area) {
                best = view
                area = size
            }
            if (view is ViewGroup && depth < 40) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
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
        snapshot?.bitmap?.let(retired::addLast)
        snapshot = null
        // Keep recently recorded frames alive across the UI/render-thread boundary.
        while (retired.size > 3) retired.removeFirst().takeUnless(Bitmap::isRecycled)?.recycle()
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
        while (retired.isNotEmpty()) retired.removeFirst().takeUnless(Bitmap::isRecycled)?.recycle()
    }

    private data class CaptureApi(val capture: Class<*>, val builder: Class<*>,
                                  val baseBuilder: Class<*>, val listener: Class<*>)
}
