package io.github.offlineglass.hook.adapters.jd

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.SystemClock
import android.os.Trace
import android.view.SurfaceControl
import android.view.View
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.rendering.NativeBackdropBlur
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.function.ObjIntConsumer
import kotlin.math.roundToInt

/** Sample the composed window strip without reading pixels back to the CPU. */
internal class JdGpuBackdropCapture(private val host: GlassHostLayout) {
    private data class Api(val capture: Class<*>, val builder: Class<*>, val base: Class<*>, val listener: Class<*>)
    private data class Request(val root: SurfaceControl, val overlay: SurfaceControl, val crop: Rect,
                               val width: Int, val height: Int)
    private val afterFrame = JdFrameCommitScheduler()
    private val hostLocation = IntArray(2)
    private val rootLocation = IntArray(2)
    private var windowRoot: View? = null
    private var windowControl: SurfaceControl? = null
    private var pending = false
    @Volatile private var serial = 0L
    private var retryAt = 0L
    @Volatile private var listener: Any? = null
    private var captureApi: Api? = null
    private var argsKey: Request? = null
    private var captureArgs: Any? = null
    private var unsupported = false
    private val timeout = Runnable { if (pending) fail("callback timeout") }

    fun request(epoch: Long, worker: () -> Handler) {
        if (unsupported || pending || SystemClock.uptimeMillis() < retryAt ||
            !host.isShown || !host.hasWindowFocus() || !host.opticalSurfaceRunningForAdapter) return
        val root = host.rootView
        afterFrame.schedule(root) { submit(root, epoch, worker()) }
    }

    private fun submit(root: View, epoch: Long, worker: Handler) {
        if (pending || !host.opticalSurfaceRunningForAdapter || !host.isShown || !host.hasWindowFocus()) return
        val overlay = host.opticalSurfaceViewForAdapter?.surfaceControl ?: return
        if (!overlay.isValid) return
        host.getLocationInWindow(hostLocation)
        root.getLocationInWindow(rootLocation)
        val left = hostLocation[0] - rootLocation[0]
        val top = hostLocation[1] - rootLocation[1]
        val density = host.resources.displayMetrics.density
        val padding = NativeBackdropBlur.padding(host.config.blurRadius * density, 24f * density).roundToInt()
        val geometry = JdCaptureGeometry.calculate(root.width, root.height, left, top, host.width, host.height,
            minOf(padding, left, top, root.width - left - host.width, root.height - top - host.height)
                .coerceAtLeast(0)) ?: return
        val window = windowSurface(root) ?: return
        if (!window.isValid || window === overlay) return
        val request = Request(window, overlay, geometry.sourceRect, geometry.targetWidth, geometry.targetHeight)
        pending = true
        val ticket = ++serial
        host.postDelayed(timeout, 350L)
        worker.post capture@{
            if (ticket != serial) return@capture
            Trace.beginSection("LiquidTab.JD.captureGpuStrip")
            try {
                val api = captureApi ?: resolve().also { captureApi = it }
                val args = argsFor(api, request)
                val consumer = ObjIntConsumer<Any?> { result, status ->
                    val bitmap = if (status == 0 && result != null) runCatching { wrap(result) }.getOrNull() else null
                    host.post publish@{
                        if (ticket != serial) {
                            bitmap?.recycle()
                            return@publish
                        }
                        if (bitmap == null) fail("capture status $status") else {
                            finish()
                            if (!host.publishHardwareOpticalFrameForAdapter(bitmap, geometry.barSource, epoch))
                                bitmap.recycle()
                        }
                    }
                }
                val callback = HiddenApiBypass.newInstance(api.listener, consumer)
                listener = callback
                val status = HiddenApiBypass.invoke(api.capture, null, "captureLayers", args, callback) as Int
                if (status != 0) host.post { if (ticket == serial) fail("submission status $status", permanent = status == -1) }
            } catch (error: Throwable) {
                host.post { if (ticket == serial) fail(error.toString(), permanent = true) }
            } finally {
                Trace.endSection()
            }
        }
    }

    private fun windowSurface(root: View): SurfaceControl? {
        if (windowRoot === root) windowControl?.takeIf { it.isValid }?.let { return it }
        return runCatching {
            val viewRoot = HiddenApiBypass.invoke(View::class.java, root, "getViewRootImpl") ?: return null
            (HiddenApiBypass.invoke(viewRoot.javaClass, viewRoot, "getSurfaceControl") as? SurfaceControl)
                ?.takeIf { it.isValid }?.also { windowRoot = root; windowControl = it }
        }.getOrElse { fail(it.toString(), permanent = true); null }
    }

    private fun resolve(): Api {
        val capture = runCatching { Class.forName("android.window.ScreenCaptureInternal") }
            .getOrElse { Class.forName("android.window.ScreenCapture") }
        return Api(capture, Class.forName(capture.name + "\$LayerCaptureArgs\$Builder"),
            Class.forName(capture.name + "\$CaptureArgs\$Builder"), Class.forName(capture.name + "\$ScreenCaptureListener"))
    }

    private fun argsFor(api: Api, request: Request): Any {
        if (argsKey == request) captureArgs?.let { return it }
        val builder = HiddenApiBypass.newInstance(api.builder, request.root)
        // The app's main buffer belongs to the root layer itself. Child-only
        // capture returns an empty strip after excluding the glass Surface.
        HiddenApiBypass.invoke(api.builder, builder, "setChildrenOnly", false)
        HiddenApiBypass.invoke(api.base, builder, "setSourceCrop", request.crop)
        HiddenApiBypass.invoke(api.base, builder, "setFrameScale",
            request.width.toFloat() / request.crop.width(), request.height.toFloat() / request.crop.height())
        HiddenApiBypass.invoke(api.base, builder, "setUid", android.os.Process.myUid().toLong())
        HiddenApiBypass.invoke(api.base, builder, "setExcludeLayers", arrayOf(request.overlay))
        return HiddenApiBypass.invoke(api.builder, builder, "build")!!.also {
            argsKey = request
            captureArgs = it
        }
    }

    private fun wrap(result: Any): Bitmap? {
        val buffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as HardwareBuffer
        return buffer.use {
            val space = result.javaClass.getMethod("getColorSpace").invoke(result) as? ColorSpace
            Bitmap.wrapHardwareBuffer(buffer, space)
        }
    }

    private fun finish() {
        host.removeCallbacks(timeout)
        pending = false
        listener = null
    }

    private fun fail(reason: String, permanent: Boolean = false) {
        ++serial
        finish()
        unsupported = permanent
        retryAt = SystemClock.uptimeMillis() + 1_000L
        XposedBridge.log("LiquidTab JD GPU backdrop unavailable: $reason")
    }

    fun cancel() {
        afterFrame.cancel()
        ++serial
        finish()
        windowRoot = null
        windowControl = null
    }
}
