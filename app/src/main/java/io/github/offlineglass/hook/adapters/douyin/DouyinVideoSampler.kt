package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.rendering.NativeBackdropBlur
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns Douyin compositor/video capture and all of its mutable probe state. */
internal class DouyinVideoSampler(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private var douyinVideoSurfaceView: SurfaceView? = null
    private var douyinVideoTextureView: TextureView? = null
    private var douyinVideoCopyInFlight = false
    @Volatile private var douyinAsyncCaptureListener: Any? = null
    private var douyinVideoCopyStartedAt = 0L
    private var douyinVideoScratch: Bitmap? = null
    private var lastDouyinVideoCopy = 0L
    private var douyinVideoSurfaceLeft = 0
    private var douyinVideoSurfaceTop = 0
    private var douyinVideoSurfaceWidth = 0
    private var douyinVideoSurfaceHeight = 0
    private var lastDouyinVideoSurfaceDiag: SurfaceView? = null
    private var douyinCopySuccessEpoch = 0
    private var douyinCopyFailEpoch = 0
    private var lastDouyinVideoFindDiagAt = 0L
    private var douyinVideoCandidates = ArrayList<SurfaceView>(8)
    private var douyinVideoProbeIndex = 0
    private var douyinVideoProbeFails = 0
    private var lastDouyinVideoScanAt = 0L
    private var douyinVideoChurn = 0
    private var douyinVideoHolderWatched: SurfaceView? = null
    private var backdrop: Bitmap? = null
    private var compositorPixelCopyThread: HandlerThread? = null
    private var compositorPixelCopyHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentHost: View? = null
    private var pageAllowed = false
    private var blocked = false
    private val captureWakeup = Runnable { requestCurrent() }
    private var captureGeneration = 0L
    private var nativeTextureActive = false
    private var lastFrameGeometryLog = 0L
    private var statsStartedAt = 0L
    private var statsFrames = 0
    private var statsLatency = 0L
    private var statsMaxLatency = 0L
    private data class CaptureApi(
        val capture: Class<*>, val builder: Class<*>,
        val baseBuilder: Class<*>, val listener: Class<*>,
    )
    // Accessed only on the capture worker.
    private var captureApi: CaptureApi? = null

    fun request(host: View, allowed: Boolean, samplingBlocked: Boolean) {
        DouyinPlayerTextureHooks.drainDiagnostics(log)
        currentHost = host
        pageAllowed = allowed
        blocked = samplingBlocked
        requestCurrent()
    }

    fun snapshot(): AppVideoBackdrop? = backdrop?.takeUnless(Bitmap::isRecycled)?.let {
        AppVideoBackdrop(it, douyinVideoSurfaceLeft, douyinVideoSurfaceTop,
            douyinVideoSurfaceWidth, douyinVideoSurfaceHeight, douyinCopySuccessEpoch)
    }

    private fun findDouyinVideoSurfaceView(root: ViewGroup): SurfaceView? {
        val host = currentHost ?: return null
        val hostLeft = IntArray(2).also(host::getLocationInWindow)
        val hostRight = hostLeft[0] + host.width
        val hostBottom = hostLeft[1] + host.height
        val hostArea = host.width.toLong() * host.height.toLong()
        val now = SystemClock.uptimeMillis()

        // Between scans, reuse the ranked candidate list: pick the current
        // probe slot when its surface is still usable, otherwise walk forward.
        if (now - lastDouyinVideoScanAt < DOUYIN_VIDEO_FIND_INTERVAL_MS) {
            selectDouyinVideoProbe()?.let { return it }
            // A swipe can detach the old SurfaceView before the one for the
            // next item is attached. Do not wait out the one-second scan window.
            lastDouyinVideoScanAt = 0L
        }

        lastDouyinVideoScanAt = now
        val minUsableArea = hostArea / 6
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        val found = ArrayList<SurfaceView>(8)
        var bestAreaSurface: SurfaceView? = null
        var bestArea = 0L
        var bestTexture: TextureView? = null
        var bestTextureArea = 0L
        var surfaceCount = 0
        var invalidSurfaceCount = 0
        var smallSurfaceCount = 0
        var textureCount = 0
        while (stack.isNotEmpty() && visited++ < DOUYIN_VIDEO_FIND_MAX_VIEWS) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) continue
            if (view is SurfaceView) {
                surfaceCount++
                if (!view.holder.surface.isValid) {
                    invalidSurfaceCount++
                } else {
                    val area = view.width.toLong() * view.height.toLong()
                    if (area < minUsableArea) {
                        smallSurfaceCount++
                    } else {
                        found.add(view)
                        if (area > bestArea) {
                            bestArea = area
                            bestAreaSurface = view
                        }
                    }
                }
            } else if (view is TextureView && view.surfaceTexture != null) {
                textureCount++
                if (now - lastDouyinVideoFindDiagAt >= 3000L) {
                    val pos = IntArray(2).also(view::getLocationInWindow)
                    var ancestor: View? = view
                    val chain = ArrayList<String>()
                    repeat(8) {
                        ancestor?.let { a ->
                            val id = runCatching { a.resources.getResourceEntryName(a.id) }.getOrDefault("-")
                            chain += "${a.javaClass.simpleName}/$id"
                            ancestor = a.parent as? View
                        }
                    }
                    douyinVideoDiag("texture-probe ${view.width}x${view.height} at=${pos[0]},${pos[1]} available=${view.isAvailable} chain=$chain")
                }
                val area = view.width.toLong() * view.height.toLong()
                if (area >= minUsableArea && area > bestTextureArea) {
                    bestTexture = view
                    bestTextureArea = area
                }
            }
            if (view is ViewGroup && depth < DOUYIN_VIDEO_FIND_MAX_DEPTH) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
            }
        }

        // Rank by overlap with this host's window rect first (the glass must
        // blur pixels directly behind the bar), then by total area. A surface
        // that never covers the bar is useless even when it is the biggest in
        // the tree (e.g. a full-screen pool or preview surface elsewhere).
        if (found.isNotEmpty()) {
            val ranked = found.sortedWith(
                compareByDescending<SurfaceView> { surface ->
                    val loc = IntArray(2).also(surface::getLocationInWindow)
                    overlapsDouyinHost(
                        loc[0], loc[1], loc[0] + surface.width, loc[1] + surface.height,
                        hostLeft[0], hostLeft[1], hostRight, hostBottom,
                    )
                }.thenByDescending { it.width.toLong() * it.height.toLong() },
            )
            douyinVideoCandidates.clear()
            douyinVideoCandidates.addAll(ranked.take(DOUYIN_VIDEO_PROBE_MAX))
            if (douyinVideoProbeIndex >= douyinVideoCandidates.size) douyinVideoProbeIndex = 0
        } else if (bestAreaSurface != null) {
            // No surface overlapped the bar; keep the largest as a fallback so
            // the copy still runs (geometry mapping handles a mid-screen view).
            douyinVideoCandidates.clear()
            douyinVideoCandidates.add(bestAreaSurface)
            douyinVideoProbeIndex = 0
        } else {
            douyinVideoCandidates.clear()
            douyinVideoProbeIndex = 0
        }

        douyinVideoSurfaceView = selectDouyinVideoProbe()
        douyinVideoTextureView = if (douyinVideoSurfaceView == null) bestTexture else null
        if (now - lastDouyinVideoFindDiagAt >= 3000L) {
            lastDouyinVideoFindDiagAt = now
            val sb = StringBuilder(
                "find: sv=$surfaceCount invalid=$invalidSurfaceCount " +
                    "small=$smallSurfaceCount tx=$textureCount visited=$visited " +
                    "minArea=$minUsableArea",
            )
            for ((i, c) in douyinVideoCandidates.withIndex()) {
                val loc = IntArray(2).also(c::getLocationInWindow)
                sb.append("\n  cand[$i] ${c.javaClass.simpleName} ${c.width}x${c.height} " +
                    "frame=${c.holder.surfaceFrame.width()}x${c.holder.surfaceFrame.height()} " +
                    "at=${loc[0]},${loc[1]}")
            }
            if (bestTexture != null) {
                sb.append("\n  texture ${bestTexture!!.javaClass.simpleName} " +
                    "${bestTexture!!.width}x${bestTexture!!.height}")
            }
            douyinVideoDiag(sb.toString())
        }
        return douyinVideoSurfaceView
    }

    /** Pick the current probe slot if usable, else walk to the next valid one. */
    private fun selectDouyinVideoProbe(): SurfaceView? {
        val list = douyinVideoCandidates
        if (list.isEmpty()) return null
        var i = douyinVideoProbeIndex
        if (i >= list.size) i = 0
        val n = list.size
        repeat(n) {
            val c = list[i]
            if (c.isAttachedToWindow && c.isShown && c.width > 0 &&
                c.height > 0 && c.holder.surface.isValid
            ) {
                if (i != douyinVideoProbeIndex) {
            douyinVideoDiag(
                        "probe-> idx=$i ${c.javaClass.simpleName} ${c.width}x${c.height}",
                    )
                    douyinVideoProbeIndex = i
                    douyinVideoProbeFails = 0
                }
                watchDouyinVideoSurface(c)
                return c
            }
            i = (i + 1) % n
        }
        return null
    }

    private val douyinVideoHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            douyinVideoDiag(
                "holder created ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}",
            )
            resetDouyinVideoProbe("holder created")
            currentHost?.post { requestCurrent() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            douyinVideoDiag("holder changed ${w}x${h}")
            resetDouyinVideoProbe("holder changed")
            currentHost?.post { requestCurrent() }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            captureGeneration++
            douyinVideoChurn++
            douyinVideoDiag("holder destroyed churn=$douyinVideoChurn")
            // The old Surface can remain reported as attached for a short
            // interval during a feed-page transition. Invalidate it at the
            // lifecycle callback instead of waiting for PixelCopy to fail.
            douyinVideoCopyInFlight = false
            douyinVideoCopyStartedAt = 0L
            resetDouyinVideoProbe("holder destroyed")
            currentHost?.post { requestCurrent() }
        }
    }

    private fun watchDouyinVideoSurface(c: SurfaceView) {
        val prev = douyinVideoHolderWatched
        if (prev !== c) {
            prev?.holder?.removeCallback(douyinVideoHolderCallback)
            c.holder.addCallback(douyinVideoHolderCallback)
            douyinVideoHolderWatched = c
        }
    }

    /**
     * Move the PixelCopy probe to the next ranked candidate after the current
     * surface rejects readback, and zero the cadence clock so the retry runs
     * on the next tick instead of waiting out the interval.
     */
    private fun advanceDouyinVideoProbe(reason: String) {
        val n = douyinVideoCandidates.size
        douyinVideoProbeFails = 0
        if (n <= 1) return
        douyinVideoProbeIndex = (douyinVideoProbeIndex + 1) % n
        lastDouyinVideoCopy = 0L
        douyinVideoDiag("advance reason=$reason idx=$douyinVideoProbeIndex")
    }

    private fun resetDouyinVideoProbe(reason: String) {
        lastDouyinVideoScanAt = 0L
        lastDouyinVideoCopy = 0L
        douyinVideoProbeIndex = 0
        douyinVideoProbeFails = 0
        douyinVideoCandidates.clear()
        douyinVideoDiag("rescan reason=$reason")
    }

    private fun overlapsDouyinHost(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int,
    ): Long {
        val overlapW = minOf(aRight, bRight) - maxOf(aLeft, bLeft)
        val overlapH = minOf(aBottom, bBottom) - maxOf(aTop, bTop)
        if (overlapW <= 0 || overlapH <= 0) return 0L
        return overlapW.toLong() * overlapH.toLong()
    }

    /** Capture live video at display-pixel resolution around the glass only. */
    private fun requestCurrent() {

        val host = currentHost ?: return
        if (!pageAllowed || !host.isAttachedToWindow || !host.isShown || blocked ||
            host.width <= 0 || host.height <= 0
        ) return

        val now = SystemClock.uptimeMillis()

        // Registered native player textures are part of the ordinary live GPU
        // scene. Capturing them would reintroduce the readback latency.
        if (DouyinPlayerTextureHooks.activeTexture(host) != null) {
            if (!nativeTextureActive) {
                nativeTextureActive = true
                captureGeneration++
                douyinVideoCopyInFlight = false
                backdrop = null
                douyinVideoDiag("path=native-texture GPU scene; capture suspended")
            }
            mainHandler.removeCallbacks(captureWakeup)
            return
        }
        if (nativeTextureActive) {
            nativeTextureActive = false
            resetDouyinVideoProbe("native texture unavailable; capture fallback")
        }

        // During a fast feed swipe Douyin detaches the old SurfaceView before
        // attaching the next one. Do not let the old async capture occupy the
        // only slot until its callback/timeout; its late frame is discarded by
        // the existing attachment checks.
        val activeProbe = douyinVideoCandidates.getOrNull(douyinVideoProbeIndex)
        if (douyinVideoCopyInFlight && (activeProbe == null ||
                !activeProbe.isAttachedToWindow || !activeProbe.holder.surface.isValid)) {
            captureGeneration++
            douyinVideoCopyInFlight = false
            douyinVideoCopyStartedAt = 0L
            resetDouyinVideoProbe("probe detached")
        }

        // Stale-callback guard: if a PixelCopy listener never fires, the
        // in-flight flag would otherwise starve the sampling cadence forever.
        // Detect it here on the next tick and reset before re-arming.
        if (douyinVideoCopyInFlight && douyinVideoCopyStartedAt != 0L &&
            now - douyinVideoCopyStartedAt > DOUYIN_VIDEO_COPY_TIMEOUT_MS
        ) {
            douyinVideoDiag("copy stale reset ${now - douyinVideoCopyStartedAt}ms")
            captureGeneration++
            douyinVideoCopyInFlight = false
            douyinVideoCopyStartedAt = 0L
        }

        if (douyinVideoCopyInFlight) return

        val remaining = DOUYIN_VIDEO_COPY_INTERVAL_MS - (now - lastDouyinVideoCopy)
        if (remaining > 0L) {
            mainHandler.removeCallbacks(captureWakeup)
            mainHandler.postDelayed(captureWakeup, remaining)
            return
        }
        mainHandler.removeCallbacks(captureWakeup)

        lastDouyinVideoCopy = now

        val root = currentHost?.rootView as? ViewGroup ?: return

        val surfaceView = findDouyinVideoSurfaceView(root)

        if (surfaceView == null) {

            // Let the TextureView fallback own the cadence timestamp. The
            // caller has just updated it, which otherwise rejects every copy.
            lastDouyinVideoCopy = 0L

            sampleDouyinVideoTextureViewFrame()

            return

        }

        if (!surfaceView.holder.surface.isValid) {
            advanceDouyinVideoProbe("surface invalid")
            return
        }

        val surfaceLocation = IntArray(2).also(surfaceView::getLocationInWindow)

        if (lastDouyinVideoSurfaceDiag != surfaceView) {

            lastDouyinVideoSurfaceDiag = surfaceView

            Log.i(

                "DouyinGlassDiag",

                "surface=${surfaceView.javaClass.simpleName} ${surfaceView.width}x${surfaceView.height} " +

                    "loc=${surfaceLocation[0]},${surfaceLocation[1]} host=${currentHost?.width}x${currentHost?.height}",

            )

        }

        // Sample the whole surface buffer. The feed player uses fixed-size
        // buffers (e.g. 720x1280) that differ from the view rect (1368x2432),
        // so srcRect must be null (whole buffer) and the destination must
        // match the surface frame size, not the view size. The draw pass maps
        // buffer pixels back to window space via douyinVideoSurfaceWidth/
        // Height (view size) so the recorded geometry stays view-based.
        val frameRect = surfaceView.holder.surfaceFrame

        val bufferWidth = if (frameRect.width() > 0) frameRect.width() else surfaceView.width

        val bufferHeight = if (frameRect.height() > 0) frameRect.height() else surfaceView.height

        if (now - lastFrameGeometryLog >= 3000L) {
            lastFrameGeometryLog = now
            douyinVideoDiag(
            "frame=${frameRect.width()}x${frameRect.height()} " +
                "view=${surfaceView.width}x${surfaceView.height} " +
                "idx=$douyinVideoProbeIndex cls=${surfaceView.javaClass.simpleName}",
        )
        }

        if (bufferWidth <= 0 || bufferHeight <= 0) return

        // PixelCopy cannot read this player's buffer on the current ROM
        // (ERROR_SOURCE_NO_DATA). Capture the actual SurfaceControl layer
        // instead; this includes the decoder's live compositor buffer without
        // creating another window or touching the control-lift layout.
        if (requestDouyinSurfaceControlFrame(surfaceView, now)) return

        val targetWidth = max(1, bufferWidth)

        val targetHeight = max(1, bufferHeight)

        val target = douyinVideoScratch

            ?.takeIf { !it.isRecycled && it.width == targetWidth && it.height == targetHeight }

            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        douyinVideoScratch = null

        douyinVideoCopyInFlight = true

        douyinVideoCopyStartedAt = now

        runCatching {

            PixelCopy.request(

                surfaceView,

                null,

                target,

                { result ->

                    douyinVideoCopyInFlight = false

                    douyinVideoCopyStartedAt = 0L

                    if (result == PixelCopy.SUCCESS && currentHost?.isAttachedToWindow == true &&

                        pageAllowed

                    ) {

                        if (douyinCopySuccessEpoch++ % 20 == 0) {

            douyinVideoDiag(
                                "copy ok=${target.width}x${target.height} " +
                                    "surface=${douyinVideoSurfaceWidth}x${douyinVideoSurfaceHeight} " +
                                    "at=${douyinVideoSurfaceLeft},${douyinVideoSurfaceTop}",
                            )
                        }

                        val previous = backdrop

                        backdrop = target
                        douyinVideoSurfaceLeft = surfaceLocation[0]
                        douyinVideoSurfaceTop = surfaceLocation[1]
                        douyinVideoSurfaceWidth = surfaceView.width
                        douyinVideoSurfaceHeight = surfaceView.height

                        if (previous !== target) {

                            douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                            douyinVideoScratch = previous

                        }

                        // The display-rate pump in onDraw repaints the glass with

                        // the freshest backdrop; postInvalidateOnAnimation keeps the

                        // transition immediate even while that pump is throttled.

                        currentHost?.postInvalidateOnAnimation()

                    } else {
                        if (result == PixelCopy.ERROR_SOURCE_INVALID ||
                            result == PixelCopy.ERROR_SOURCE_NO_DATA
                        ) {
                            douyinVideoProbeFails++
                            if (douyinVideoProbeFails >= 2 && douyinVideoCandidates.size > 1) {
                                advanceDouyinVideoProbe("copy code=$result")
                            }
                        }
                        if (douyinCopyFailEpoch++ % 5 == 0) {
            douyinVideoDiag(
                                "copy fail code=$result idx=$douyinVideoProbeIndex " +
                                    "cls=${surfaceView.javaClass.simpleName} " +
                                    "${surfaceView.width}x${surfaceView.height}",
                            )
                        }

                        resetDouyinVideoProbe("copy code=$result")

                        if (target !== backdrop) {

                            douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                            douyinVideoScratch = target

                        }

                    }

                },
                mainHandler,
            )

        }.onFailure { t ->

            douyinVideoDiag("copy sync fail: ${t::class.java.simpleName}: ${t.message}")

            douyinVideoCopyInFlight = false

            douyinVideoCopyStartedAt = 0L

            if (target !== backdrop) {

                douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                douyinVideoScratch = target

            }

        }

    }

    private var douyinOpticalDrawEpoch = -1

    private fun requestDouyinSurfaceControlFrame(surfaceView: SurfaceView, startedAt: Long): Boolean {
        if (Build.VERSION.SDK_INT < 29 ||
            !surfaceView.isAttachedToWindow) return false
        val control = runCatching { surfaceView.surfaceControl }.getOrNull()
            ?.takeIf { it.isValid } ?: return false
        // Snapshot geometry on the UI thread, publish it atomically with its frame.
        val location = IntArray(2).also(surfaceView::getLocationInWindow)
        val sourceWidth = surfaceView.width
        val sourceHeight = surfaceView.height
        val host = currentHost ?: return false
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        // Use exactly the static renderer's blur margin. Reading the host's
        // existing config needs no app-specific changes to the shared renderer.
        val density = context.resources.displayMetrics.density
        val radius = ((host as? GlassHostLayout)?.config?.blurRadius ?: 16f) *
            density * DouyinAdapter.secondaryBlurScale
        val padding = kotlin.math.ceil(NativeBackdropBlur.padding(radius, 24f * density)).toInt() + 2
        val crop = Rect(
            maxOf(0, hostLocation[0] - padding - location[0]),
            maxOf(0, hostLocation[1] - padding - location[1]),
            minOf(sourceWidth, hostLocation[0] + host.width + padding - location[0]),
            minOf(sourceHeight, hostLocation[1] + host.height + padding - location[1]),
        )
        if (crop.isEmpty) {
            // A letterboxed video may not cover the bar at all. The ordinary
            // View backdrop is authoritative there; don't retry PixelCopy or
            // retain pixels from the preceding full-screen video.
            if (backdrop != null) {
                douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                douyinVideoScratch = backdrop
                backdrop = null
                douyinCopySuccessEpoch++
                host.postInvalidateOnAnimation()
            }
            return true
        }
        val requestGeneration = ++captureGeneration
        val worker = compositorPixelCopyHandler ?: run {
            val thread = HandlerThread("LiquidTab-Douyin-SurfaceCapture")
            thread.start()
            compositorPixelCopyThread = thread
            Handler(thread.looper).also { compositorPixelCopyHandler = it }
        }
        douyinVideoCopyInFlight = true
        douyinVideoCopyStartedAt = startedAt
        worker.post {
            runCatching {
                val api = captureApi ?: run {
                  val captureClass = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { Class.forName("android.window.ScreenCaptureInternal") }
                        .getOrElse { Class.forName("android.window.ScreenCapture") }
                } else SurfaceControl::class.java
                val builderClass = Class.forName(captureClass.name + "\$LayerCaptureArgs\$Builder")
                val baseBuilder = Class.forName(captureClass.name + "\$CaptureArgs\$Builder")
                  CaptureApi(captureClass, builderClass, baseBuilder,
                      Class.forName(captureClass.name + "\$ScreenCaptureListener"))
                      .also { captureApi = it }
                }
                val captureClass = api.capture
                val builderClass = api.builder
                val baseBuilder = api.baseBuilder
                val builder = org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(builderClass, control)
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setSourceCrop", crop)
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setFrameScale", DOUYIN_VIDEO_CAPTURE_SCALE)
                // The default UID (-1) requests other apps' layers and is denied.
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setUid", android.os.Process.myUid().toLong())
                val args = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(builderClass, builder, "build")
                val listenerClass = api.listener
                val consumer = java.util.function.ObjIntConsumer<Any> { capture, status ->
                    var failure: String? = null
                    val frame = if (status == 0 && capture != null) runCatching {
                        val hb = capture.javaClass.getMethod("getHardwareBuffer")
                            .invoke(capture) as android.hardware.HardwareBuffer
                        try {
                            val cs = capture.javaClass.getMethod("getColorSpace")
                                .invoke(capture) as? android.graphics.ColorSpace
                            Bitmap.wrapHardwareBuffer(hb, cs)
                                ?: error("wrapHardwareBuffer failed")
                        } finally {
                            hb.close()
                        }
                    }.onFailure {
                        failure = "${it.javaClass.simpleName}: ${it.message}; cause=${it.cause}"
                    }.getOrNull() else null
                    mainHandler.post completion@ {
                        if (requestGeneration != captureGeneration) {
                            frame?.recycle()
                            return@completion
                        }
                        douyinAsyncCaptureListener = null
                        douyinVideoCopyInFlight = false
                        douyinVideoCopyStartedAt = 0L
                        if (frame != null && currentHost?.isAttachedToWindow == true && surfaceView.isAttachedToWindow &&
                            surfaceView.isShown && pageAllowed
                        ) {
                            val previous = backdrop
                            backdrop = frame
                            douyinVideoSurfaceLeft = location[0] + crop.left
                            douyinVideoSurfaceTop = location[1] + crop.top
                            douyinVideoSurfaceWidth = crop.width()
                            douyinVideoSurfaceHeight = crop.height()
                            douyinCopySuccessEpoch++
                            douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                            douyinVideoScratch = previous
                            val completedAt = SystemClock.uptimeMillis()
                            if (statsStartedAt == 0L) statsStartedAt = startedAt
                            statsFrames++
                            statsLatency += completedAt - startedAt
                            statsMaxLatency = maxOf(statsMaxLatency, completedAt - startedAt)
                            if (completedAt - statsStartedAt >= 3000L) {
                                douyinVideoDiag("capture-window frames=$statsFrames fps=${statsFrames * 1000L / (completedAt - statsStartedAt)} avg=${statsLatency / statsFrames}ms max=${statsMaxLatency}ms size=${frame.width}x${frame.height} crop=$crop hardware=${frame.config == Bitmap.Config.HARDWARE}")
                                statsStartedAt = completedAt
                                statsFrames = 0
                                statsLatency = 0L
                                statsMaxLatency = 0L
                            }
                            currentHost?.postInvalidateOnAnimation()
                            requestCurrent()
                        } else {
                            frame?.recycle()
                            douyinVideoDiag("async capture failed status=$status ${failure.orEmpty()}")
                            resetDouyinVideoProbe("async status=$status")
                            mainHandler.postDelayed(captureWakeup, 16L)
                        }
                    }
                }
                val listener = org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(listenerClass, consumer)
                douyinAsyncCaptureListener = listener
                val status = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                    captureClass,
                    null,
                    "captureLayers",
                    args,
                    listener,
                ) as Int
                check(status == 0) { "async capture status=$status" }
            }.onFailure { error ->
                mainHandler.post failure@ {
                    if (requestGeneration != captureGeneration) return@failure
                    douyinAsyncCaptureListener = null
                    douyinVideoCopyInFlight = false
                    douyinVideoCopyStartedAt = 0L
                    douyinVideoDiag("async submit failed ${error.javaClass.simpleName}: ${error.message}")
                    resetDouyinVideoProbe("submit failure")
                    mainHandler.postDelayed(captureWakeup, 16L)
                }
            }
        }
        return true
    }
    /**
     * TextureView fallback for the Douyin video readback path, used only when
     * no usable SurfaceView is found in the feed tree. TextureView.getBitmap
     * is a blocking GPU readback, so it runs on the compositor worker thread
     * and the finished frame is marshalled back to the UI thread.
     */
    private fun sampleDouyinVideoTextureViewFrame() {

        val textureView = douyinVideoTextureView ?: return

        if (!textureView.isAttachedToWindow || !textureView.isShown ||
            textureView.width <= 0 || textureView.height <= 0 ||
            textureView.surfaceTexture == null
        ) return

        val now = SystemClock.uptimeMillis()

        if (now - lastDouyinVideoCopy < DOUYIN_VIDEO_COPY_INTERVAL_MS) return

        lastDouyinVideoCopy = now

        val targetWidth = max(1, (textureView.width / CAPTURE_DOWNSCALE).roundToInt())

        val targetHeight = max(1, (textureView.height / CAPTURE_DOWNSCALE).roundToInt())

        val tv = textureView

        val worker = compositorPixelCopyHandler

        if (worker == null || !worker.post {

            val frame: Bitmap? = try {

                tv.getBitmap(targetWidth, targetHeight)

            } catch (t: Throwable) {

                null

            }

            mainHandler.post {

                if (frame != null && !frame.isRecycled && currentHost?.isAttachedToWindow == true &&

                    pageAllowed
                ) {

                    val loc = IntArray(2).also(tv::getLocationInWindow)

                    douyinVideoSurfaceLeft = loc[0]

                    douyinVideoSurfaceTop = loc[1]

                    douyinVideoSurfaceWidth = tv.width

                    douyinVideoSurfaceHeight = tv.height

                    val previous = backdrop

                    backdrop = frame

                    douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                    douyinVideoScratch = previous

                    currentHost?.postInvalidateOnAnimation()

                } else {

                    frame?.takeUnless(Bitmap::isRecycled)?.recycle()

                }

            }

        }) { }

    }

    /**
     * PixelCopy the UnicornSurfaceView at the glass host's real screen position.

     * Because this Surface contains only Taobao's independently composed page,

     * it never includes our overlay and therefore cannot recursively sample the

     * glass. Sampling the strip above the host (the former Window workaround)

     * produced a visible vertical mismatch between refraction and the feed.

     */


    fun dispose() {
        captureGeneration++
        mainHandler.removeCallbacks(captureWakeup)
        currentHost = null
        pageAllowed = false
        douyinVideoHolderWatched?.holder?.removeCallback(douyinVideoHolderCallback)
        douyinVideoHolderWatched = null
        compositorPixelCopyThread?.quitSafely()
        compositorPixelCopyThread = null
        compositorPixelCopyHandler = null
        douyinVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
        douyinVideoScratch = null
        backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdrop = null
    }

    private fun douyinVideoDiag(message: String) = log(message)

    private companion object {
        const val DOUYIN_VIDEO_COPY_INTERVAL_MS = 8L
        const val DOUYIN_VIDEO_CAPTURE_SCALE = 1f
        const val DOUYIN_VIDEO_COPY_TIMEOUT_MS = 250L
        const val DOUYIN_VIDEO_FIND_INTERVAL_MS = 200L
        const val DOUYIN_VIDEO_PROBE_MAX = 4
        const val DOUYIN_VIDEO_FIND_MAX_VIEWS = 40_000
        const val DOUYIN_VIDEO_FIND_MAX_DEPTH = 48
        const val CAPTURE_DOWNSCALE = 2f
    }
}
