package io.github.offlineglass.hook.adapters.taobao

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
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns Taobao compositor/video capture and all of its mutable probe state. */
internal class TaobaoVideoSampler(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    private var taobaoVideoSurfaceView: SurfaceView? = null
    private var taobaoVideoTextureView: TextureView? = null
    private var taobaoVideoCopyInFlight = false
    @Volatile private var taobaoAsyncCaptureListener: Any? = null
    private var taobaoVideoCopyStartedAt = 0L
    private var taobaoVideoScratch: Bitmap? = null
    private var lastTaobaoVideoCopy = 0L
    private var taobaoVideoSurfaceLeft = 0
    private var taobaoVideoSurfaceTop = 0
    private var taobaoVideoSurfaceWidth = 0
    private var taobaoVideoSurfaceHeight = 0
    private var lastTaobaoVideoSurfaceDiag: SurfaceView? = null
    private var taobaoCopySuccessEpoch = 0
    private var taobaoCopyFailEpoch = 0
    private var lastTaobaoVideoFindDiagAt = 0L
    private var taobaoVideoCandidates = ArrayList<SurfaceView>(8)
    private var taobaoVideoProbeIndex = 0
    private var taobaoVideoProbeFails = 0
    private var lastTaobaoVideoScanAt = 0L
    private var taobaoVideoChurn = 0
    private var taobaoVideoHolderWatched: SurfaceView? = null
    private var backdrop: Bitmap? = null
    private var compositorPixelCopyThread: HandlerThread? = null
    private var compositorPixelCopyHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentHost: View? = null
    private var pageAllowed = false
    private var blocked = false

    fun request(host: View, allowed: Boolean, samplingBlocked: Boolean) {
        currentHost = host
        pageAllowed = allowed
        blocked = samplingBlocked
        requestCurrent()
    }

    fun snapshot(): AppVideoBackdrop? = backdrop?.takeUnless(Bitmap::isRecycled)?.let {
        AppVideoBackdrop(it, taobaoVideoSurfaceLeft, taobaoVideoSurfaceTop,
            taobaoVideoSurfaceWidth, taobaoVideoSurfaceHeight, taobaoCopySuccessEpoch)
    }

    private fun findTaobaoVideoSurfaceView(root: ViewGroup): SurfaceView? {
        val host = currentHost ?: return null
        val hostLeft = IntArray(2).also(host::getLocationInWindow)
        val hostRight = hostLeft[0] + host.width
        val hostBottom = hostLeft[1] + host.height
        val hostArea = host.width.toLong() * host.height.toLong()
        val now = SystemClock.uptimeMillis()

        // Between scans, reuse the ranked candidate list: pick the current
        // probe slot when its surface is still usable, otherwise walk forward.
        if (now - lastTaobaoVideoScanAt < TAOBAO_VIDEO_FIND_INTERVAL_MS) {
            selectTaobaoVideoProbe()?.let { return it }
            // A swipe can detach the old SurfaceView before the one for the
            // next item is attached. Do not wait out the one-second scan window.
            lastTaobaoVideoScanAt = 0L
        }

        lastTaobaoVideoScanAt = now
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
        while (stack.isNotEmpty() && visited++ < TAOBAO_VIDEO_FIND_MAX_VIEWS) {
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
                val area = view.width.toLong() * view.height.toLong()
                if (area >= minUsableArea && area > bestTextureArea) {
                    bestTexture = view
                    bestTextureArea = area
                }
            }
            if (view is ViewGroup && depth < TAOBAO_VIDEO_FIND_MAX_DEPTH) {
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
                    overlapsTaobaoHost(
                        loc[0], loc[1], loc[0] + surface.width, loc[1] + surface.height,
                        hostLeft[0], hostLeft[1], hostRight, hostBottom,
                    )
                }.thenByDescending { it.width.toLong() * it.height.toLong() },
            )
            taobaoVideoCandidates.clear()
            taobaoVideoCandidates.addAll(ranked.take(TAOBAO_VIDEO_PROBE_MAX))
            if (taobaoVideoProbeIndex >= taobaoVideoCandidates.size) taobaoVideoProbeIndex = 0
        } else if (bestAreaSurface != null) {
            // No surface overlapped the bar; keep the largest as a fallback so
            // the copy still runs (geometry mapping handles a mid-screen view).
            taobaoVideoCandidates.clear()
            taobaoVideoCandidates.add(bestAreaSurface)
            taobaoVideoProbeIndex = 0
        } else {
            taobaoVideoCandidates.clear()
            taobaoVideoProbeIndex = 0
        }

        taobaoVideoSurfaceView = selectTaobaoVideoProbe()
        taobaoVideoTextureView = if (taobaoVideoSurfaceView == null) bestTexture else null
        if (now - lastTaobaoVideoFindDiagAt >= 3000L) {
            lastTaobaoVideoFindDiagAt = now
            val sb = StringBuilder(
                "find: sv=$surfaceCount invalid=$invalidSurfaceCount " +
                    "small=$smallSurfaceCount tx=$textureCount visited=$visited " +
                    "minArea=$minUsableArea",
            )
            for ((i, c) in taobaoVideoCandidates.withIndex()) {
                val loc = IntArray(2).also(c::getLocationInWindow)
                sb.append("\n  cand[$i] ${c.javaClass.simpleName} ${c.width}x${c.height} " +
                    "frame=${c.holder.surfaceFrame.width()}x${c.holder.surfaceFrame.height()} " +
                    "at=${loc[0]},${loc[1]}")
            }
            if (bestTexture != null) {
                sb.append("\n  texture ${bestTexture!!.javaClass.simpleName} " +
                    "${bestTexture!!.width}x${bestTexture!!.height}")
            }
            taobaoVideoDiag(sb.toString())
        }
        return taobaoVideoSurfaceView
    }

    /** Pick the current probe slot if usable, else walk to the next valid one. */
    private fun selectTaobaoVideoProbe(): SurfaceView? {
        val list = taobaoVideoCandidates
        if (list.isEmpty()) return null
        var i = taobaoVideoProbeIndex
        if (i >= list.size) i = 0
        val n = list.size
        repeat(n) {
            val c = list[i]
            if (c.isAttachedToWindow && c.isShown && c.width > 0 &&
                c.height > 0 && c.holder.surface.isValid
            ) {
                if (i != taobaoVideoProbeIndex) {
            taobaoVideoDiag(
                        "probe-> idx=$i ${c.javaClass.simpleName} ${c.width}x${c.height}",
                    )
                    taobaoVideoProbeIndex = i
                    taobaoVideoProbeFails = 0
                }
                watchTaobaoVideoSurface(c)
                return c
            }
            i = (i + 1) % n
        }
        return null
    }

    private val taobaoVideoHolderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            taobaoVideoDiag(
                "holder created ${holder.surfaceFrame.width()}x${holder.surfaceFrame.height()}",
            )
            resetTaobaoVideoProbe("holder created")
            currentHost?.post { requestCurrent() }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            taobaoVideoDiag("holder changed ${w}x${h}")
            resetTaobaoVideoProbe("holder changed")
            currentHost?.post { requestCurrent() }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            taobaoVideoChurn++
            taobaoVideoDiag("holder destroyed churn=$taobaoVideoChurn")
            // The old Surface can remain reported as attached for a short
            // interval during a feed-page transition. Invalidate it at the
            // lifecycle callback instead of waiting for PixelCopy to fail.
            taobaoVideoCopyInFlight = false
            taobaoVideoCopyStartedAt = 0L
            resetTaobaoVideoProbe("holder destroyed")
            currentHost?.post { requestCurrent() }
        }
    }

    private fun watchTaobaoVideoSurface(c: SurfaceView) {
        val prev = taobaoVideoHolderWatched
        if (prev !== c) {
            prev?.holder?.removeCallback(taobaoVideoHolderCallback)
            c.holder.addCallback(taobaoVideoHolderCallback)
            taobaoVideoHolderWatched = c
        }
    }

    /**
     * Move the PixelCopy probe to the next ranked candidate after the current
     * surface rejects readback, and zero the cadence clock so the retry runs
     * on the next tick instead of waiting out the interval.
     */
    private fun advanceTaobaoVideoProbe(reason: String) {
        val n = taobaoVideoCandidates.size
        taobaoVideoProbeFails = 0
        if (n <= 1) return
        taobaoVideoProbeIndex = (taobaoVideoProbeIndex + 1) % n
        lastTaobaoVideoCopy = 0L
        taobaoVideoDiag("advance reason=$reason idx=$taobaoVideoProbeIndex")
    }

    private fun resetTaobaoVideoProbe(reason: String) {
        lastTaobaoVideoScanAt = 0L
        lastTaobaoVideoCopy = 0L
        taobaoVideoProbeIndex = 0
        taobaoVideoProbeFails = 0
        taobaoVideoCandidates.clear()
        taobaoVideoDiag("rescan reason=$reason")
    }

    private fun overlapsTaobaoHost(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int,
    ): Long {
        val overlapW = minOf(aRight, bRight) - maxOf(aLeft, bLeft)
        val overlapH = minOf(aBottom, bBottom) - maxOf(aTop, bTop)
        if (overlapW <= 0 || overlapH <= 0) return 0L
        return overlapW.toLong() * overlapH.toLong()
    }

    /**
     * Low-frequency PixelCopy of the Taobao video surface into `backdrop`. The
     * source rectangle is the liquid bar's own screen position inside the
     * video surface (plus the usual optical sampling pad), so the glass
     * refracts exactly the pixels behind it. Taobao video changes every frame,
     * so the copy runs on a fixed ~10 fps interval instead of a
     * static-backoff cadence; the blur makes the lower rate visually
     * indistinguishable from a display-rate capture.
     */
    private fun requestCurrent() {

        val host = currentHost ?: return
        if (!pageAllowed || !host.isAttachedToWindow || !host.isShown || blocked ||
            host.width <= 0 || host.height <= 0
        ) return

        val now = SystemClock.uptimeMillis()

        // During a fast feed swipe Taobao detaches the old SurfaceView before
        // attaching the next one. Do not let the old async capture occupy the
        // only slot until its callback/timeout; its late frame is discarded by
        // the existing attachment checks.
        val activeProbe = taobaoVideoCandidates.getOrNull(taobaoVideoProbeIndex)
        if (taobaoVideoCopyInFlight && (activeProbe == null ||
                !activeProbe.isAttachedToWindow || !activeProbe.holder.surface.isValid)) {
            taobaoVideoCopyInFlight = false
            taobaoVideoCopyStartedAt = 0L
            resetTaobaoVideoProbe("probe detached")
        }

        // Stale-callback guard: if a PixelCopy listener never fires, the
        // in-flight flag would otherwise starve the sampling cadence forever.
        // Detect it here on the next tick and reset before re-arming.
        if (taobaoVideoCopyInFlight && taobaoVideoCopyStartedAt != 0L &&
            now - taobaoVideoCopyStartedAt > TAOBAO_VIDEO_COPY_TIMEOUT_MS
        ) {
            taobaoVideoDiag("copy stale reset ${now - taobaoVideoCopyStartedAt}ms")
            taobaoVideoCopyInFlight = false
            taobaoVideoCopyStartedAt = 0L
        }

        if (taobaoVideoCopyInFlight) return

        if (now - lastTaobaoVideoCopy < TAOBAO_VIDEO_COPY_INTERVAL_MS) return

        lastTaobaoVideoCopy = now

        val root = currentHost?.rootView as? ViewGroup ?: return

        val surfaceView = findTaobaoVideoSurfaceView(root)

        if (surfaceView == null) {

            // Let the TextureView fallback own the cadence timestamp. The
            // caller has just updated it, which otherwise rejects every copy.
            lastTaobaoVideoCopy = 0L

            sampleTaobaoVideoTextureViewFrame()

            return

        }

        if (!surfaceView.holder.surface.isValid) {
            advanceTaobaoVideoProbe("surface invalid")
            return
        }

        val surfaceLocation = IntArray(2).also(surfaceView::getLocationInWindow)

        if (lastTaobaoVideoSurfaceDiag != surfaceView) {

            lastTaobaoVideoSurfaceDiag = surfaceView

            Log.i(

                "TaobaoGlassDiag",

                "surface=${surfaceView.javaClass.simpleName} ${surfaceView.width}x${surfaceView.height} " +

                    "loc=${surfaceLocation[0]},${surfaceLocation[1]} host=${currentHost?.width}x${currentHost?.height}",

            )

        }

        // Sample the whole surface buffer. The feed player uses fixed-size
        // buffers (e.g. 720x1280) that differ from the view rect (1368x2432),
        // so srcRect must be null (whole buffer) and the destination must
        // match the surface frame size, not the view size. The draw pass maps
        // buffer pixels back to window space via taobaoVideoSurfaceWidth/
        // Height (view size) so the recorded geometry stays view-based.
        val frameRect = surfaceView.holder.surfaceFrame

        val bufferWidth = if (frameRect.width() > 0) frameRect.width() else surfaceView.width

        val bufferHeight = if (frameRect.height() > 0) frameRect.height() else surfaceView.height

            taobaoVideoDiag(
            "frame=${frameRect.width()}x${frameRect.height()} " +
                "view=${surfaceView.width}x${surfaceView.height} " +
                "idx=$taobaoVideoProbeIndex cls=${surfaceView.javaClass.simpleName}",
        )

        if (bufferWidth <= 0 || bufferHeight <= 0) return

        taobaoVideoSurfaceLeft = surfaceLocation[0]

        taobaoVideoSurfaceTop = surfaceLocation[1]

        taobaoVideoSurfaceWidth = surfaceView.width

        taobaoVideoSurfaceHeight = surfaceView.height

        // PixelCopy cannot read this player's buffer on the current ROM
        // (ERROR_SOURCE_NO_DATA). Capture the actual SurfaceControl layer
        // instead; this includes the decoder's live compositor buffer without
        // creating another window or touching the control-lift layout.
        if (requestTaobaoSurfaceControlFrame(surfaceView, now)) return

        val targetWidth = max(1, bufferWidth)

        val targetHeight = max(1, bufferHeight)

        val target = taobaoVideoScratch

            ?.takeIf { !it.isRecycled && it.width == targetWidth && it.height == targetHeight }

            ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)

        taobaoVideoScratch = null

        taobaoVideoCopyInFlight = true

        taobaoVideoCopyStartedAt = now

        runCatching {

            PixelCopy.request(

                surfaceView,

                null,

                target,

                { result ->

                    taobaoVideoCopyInFlight = false

                    taobaoVideoCopyStartedAt = 0L

                    if (result == PixelCopy.SUCCESS && currentHost?.isAttachedToWindow == true &&

                        pageAllowed

                    ) {

                        if (taobaoCopySuccessEpoch++ % 20 == 0) {

            taobaoVideoDiag(
                                "copy ok=${target.width}x${target.height} " +
                                    "surface=${taobaoVideoSurfaceWidth}x${taobaoVideoSurfaceHeight} " +
                                    "at=${taobaoVideoSurfaceLeft},${taobaoVideoSurfaceTop}",
                            )
                        }

                        val previous = backdrop

                        backdrop = target

                        if (previous !== target) {

                            taobaoVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                            taobaoVideoScratch = previous

                        }

                        // The display-rate pump in onDraw repaints the glass with

                        // the freshest backdrop; postInvalidateOnAnimation keeps the

                        // transition immediate even while that pump is throttled.

                        currentHost?.postInvalidateOnAnimation()

                    } else {
                        if (result == PixelCopy.ERROR_SOURCE_INVALID ||
                            result == PixelCopy.ERROR_SOURCE_NO_DATA
                        ) {
                            taobaoVideoProbeFails++
                            if (taobaoVideoProbeFails >= 2 && taobaoVideoCandidates.size > 1) {
                                advanceTaobaoVideoProbe("copy code=$result")
                            }
                        }
                        if (taobaoCopyFailEpoch++ % 5 == 0) {
            taobaoVideoDiag(
                                "copy fail code=$result idx=$taobaoVideoProbeIndex " +
                                    "cls=${surfaceView.javaClass.simpleName} " +
                                    "${surfaceView.width}x${surfaceView.height}",
                            )
                        }

                        resetTaobaoVideoProbe("copy code=$result")

                        if (target !== backdrop) {

                            taobaoVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                            taobaoVideoScratch = target

                        }

                    }

                },
                mainHandler,
            )

        }.onFailure { t ->

            taobaoVideoDiag("copy sync fail: ${t::class.java.simpleName}: ${t.message}")

            taobaoVideoCopyInFlight = false

            taobaoVideoCopyStartedAt = 0L

            if (target !== backdrop) {

                taobaoVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                taobaoVideoScratch = target

            }

        }

    }

    private var taobaoOpticalDrawEpoch = -1

    private fun requestTaobaoSurfaceControlFrame(surfaceView: SurfaceView, startedAt: Long): Boolean {
        if (Build.VERSION.SDK_INT < 29 ||
            !surfaceView.isAttachedToWindow) return false
        val control = runCatching { surfaceView.surfaceControl }.getOrNull()
            ?.takeIf { it.isValid } ?: return false
        // Snapshot geometry on the UI thread, publish it atomically with its frame.
        val location = IntArray(2).also(surfaceView::getLocationInWindow)
        val sourceWidth = surfaceView.width
        val sourceHeight = surfaceView.height
        val worker = compositorPixelCopyHandler ?: run {
            val thread = HandlerThread("LiquidTab-Taobao-SurfaceCapture")
            thread.start()
            compositorPixelCopyThread = thread
            Handler(thread.looper).also { compositorPixelCopyHandler = it }
        }
        taobaoVideoCopyInFlight = true
        taobaoVideoCopyStartedAt = startedAt
        worker.post {
            runCatching {
                val captureClass = if (Build.VERSION.SDK_INT >= 34) {
                    runCatching { Class.forName("android.window.ScreenCaptureInternal") }
                        .getOrElse { Class.forName("android.window.ScreenCapture") }
                } else SurfaceControl::class.java
                val builderClass = Class.forName(captureClass.name + "\$LayerCaptureArgs\$Builder")
                val baseBuilder = Class.forName(captureClass.name + "\$CaptureArgs\$Builder")
                val builder = org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(builderClass, control)
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setSourceCrop", Rect(0, 0, sourceWidth, sourceHeight))
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setFrameScale", TAOBAO_VIDEO_CAPTURE_SCALE)
                // The default UID (-1) requests other apps' layers and is denied.
                org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(baseBuilder, builder, "setUid", android.os.Process.myUid().toLong())
                val args = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(builderClass, builder, "build")
                val listenerClass = Class.forName(captureClass.name + "\$ScreenCaptureListener")
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
                    mainHandler.post {
                        taobaoAsyncCaptureListener = null
                        taobaoVideoCopyInFlight = false
                        taobaoVideoCopyStartedAt = 0L
                        if (frame != null && currentHost?.isAttachedToWindow == true && surfaceView.isAttachedToWindow &&
                            surfaceView.isShown && pageAllowed
                        ) {
                            val previous = backdrop
                            backdrop = frame
                            taobaoVideoSurfaceLeft = location[0]
                            taobaoVideoSurfaceTop = location[1]
                            taobaoVideoSurfaceWidth = sourceWidth
                            taobaoVideoSurfaceHeight = sourceHeight
                            taobaoCopySuccessEpoch++
                            taobaoVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
                            taobaoVideoScratch = previous
                            if (taobaoCopySuccessEpoch % 15 == 0) {
                                taobaoVideoDiag("async capture ok epoch=$taobaoCopySuccessEpoch hardware=${frame.config == Bitmap.Config.HARDWARE} size=${frame.width}x${frame.height} elapsed=${SystemClock.uptimeMillis()-startedAt}ms")
                            }
                            currentHost?.postInvalidateOnAnimation()
                            requestCurrent()
                        } else {
                            frame?.recycle()
                            taobaoVideoDiag("async capture failed status=$status ${failure.orEmpty()}")
                            resetTaobaoVideoProbe("async status=$status")
                            currentHost?.post { requestCurrent() }
                        }
                    }
                }
                val listener = org.lsposed.hiddenapibypass.HiddenApiBypass.newInstance(listenerClass, consumer)
                taobaoAsyncCaptureListener = listener
                val status = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                    captureClass,
                    null,
                    "captureLayers",
                    args,
                    listener,
                ) as Int
                check(status == 0) { "async capture status=$status" }
            }.onFailure { error ->
                mainHandler.post {
                    taobaoAsyncCaptureListener = null
                    taobaoVideoCopyInFlight = false
                    taobaoVideoCopyStartedAt = 0L
                    taobaoVideoDiag("async submit failed ${error.javaClass.simpleName}: ${error.message}")
                    resetTaobaoVideoProbe("submit failure")
                    currentHost?.post { requestCurrent() }
                }
            }
        }
        return true
    }
    /**
     * TextureView fallback for the Taobao video readback path, used only when
     * no usable SurfaceView is found in the feed tree. TextureView.getBitmap
     * is a blocking GPU readback, so it runs on the compositor worker thread
     * and the finished frame is marshalled back to the UI thread.
     */
    private fun sampleTaobaoVideoTextureViewFrame() {

        val textureView = taobaoVideoTextureView ?: return

        if (!textureView.isAttachedToWindow || !textureView.isShown ||
            textureView.width <= 0 || textureView.height <= 0 ||
            textureView.surfaceTexture == null
        ) return

        val now = SystemClock.uptimeMillis()

        if (now - lastTaobaoVideoCopy < TAOBAO_VIDEO_COPY_INTERVAL_MS) return

        lastTaobaoVideoCopy = now

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

                    taobaoVideoSurfaceLeft = loc[0]

                    taobaoVideoSurfaceTop = loc[1]

                    taobaoVideoSurfaceWidth = tv.width

                    taobaoVideoSurfaceHeight = tv.height

                    val previous = backdrop

                    backdrop = frame

                    taobaoVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()

                    taobaoVideoScratch = previous

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
        taobaoVideoHolderWatched?.holder?.removeCallback(taobaoVideoHolderCallback)
        taobaoVideoHolderWatched = null
        compositorPixelCopyThread?.quitSafely()
        compositorPixelCopyThread = null
        compositorPixelCopyHandler = null
        taobaoVideoScratch?.takeUnless(Bitmap::isRecycled)?.recycle()
        taobaoVideoScratch = null
        backdrop?.takeUnless(Bitmap::isRecycled)?.recycle()
        backdrop = null
    }

    private fun taobaoVideoDiag(message: String) = log(message)

    private companion object {
        const val TAOBAO_VIDEO_COPY_INTERVAL_MS = 8L
        const val TAOBAO_VIDEO_CAPTURE_SCALE = 0.35f
        const val TAOBAO_VIDEO_COPY_TIMEOUT_MS = 250L
        const val TAOBAO_VIDEO_FIND_INTERVAL_MS = 200L
        const val TAOBAO_VIDEO_PROBE_MAX = 4
        // Keep probes bounded like the pre-migration Taobao path; a full feed
        // hierarchy walk on every channel transition stalls the UI thread.
        const val TAOBAO_VIDEO_FIND_MAX_VIEWS = 1_600
        const val TAOBAO_VIDEO_FIND_MAX_DEPTH = 32
        const val CAPTURE_DOWNSCALE = 2f
    }
}


