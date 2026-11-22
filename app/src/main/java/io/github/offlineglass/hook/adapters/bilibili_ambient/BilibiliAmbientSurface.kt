package io.github.offlineglass.hook.adapters.bilibili_ambient

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.SurfaceView
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.function.ObjIntConsumer
import kotlin.math.ceil
import kotlin.math.roundToInt

internal const val AMBIENT_BLUR_DP = 36f
internal const val AMBIENT_UPDATE_MS = 4000L

/** Low-frequency GPU thumbnails of the app's verified video layer. */
internal class BilibiliAmbientSurface(private val changed: () -> Unit) {
    private data class Request(val control: SurfaceControl, val crop: Rect, val width: Int, val height: Int)
    private val main = Handler(Looper.getMainLooper())
    private val flags by lazy {
        SurfaceView::class.java.getDeclaredField("mSurfaceFlags").apply { isAccessible = true }
    }
    private var request: Request? = null
    private var captureArgs: Any? = null
    private val captureApi by lazy {
        runCatching { Class.forName("android.window.ScreenCaptureInternal") }
            .getOrElse { Class.forName("android.window.ScreenCapture") }
    }
    private val listenerType by lazy { Class.forName(captureApi.name + "\$ScreenCaptureListener") }
    private var listener: Any? = null
    private var serial = 0L
    private var nextAt = 0L
    private var failures = 0
    private val timeout = Runnable { if (listener != null) finish(serial, null, "thumbnail callback timed out") }
    var bitmap: Bitmap? = null
        private set
    var failure: String? = null
        private set
    val canRetry: Boolean get() = failures < 3

    fun update(source: SurfaceView, bounds: Rect, width: Int, height: Int): Boolean {
        val control = source.surfaceControl?.takeIf { it.isValid } ?: return false
        if (width <= 0 || height <= 0 || bounds.isEmpty) return false
        if (!runCatching { flags.getInt(source) and 128 == 0 }.getOrDefault(false)) return false
        val scale = maxOf(width.toFloat() / bounds.width(), height.toFloat() / bounds.height())
        val cropWidth = (width / scale).roundToInt().coerceIn(1, bounds.width())
        val cropHeight = (height / scale).roundToInt().coerceIn(1, bounds.height())
        val left = bounds.left + (bounds.width() - cropWidth) / 2
        val next = Request(control, Rect(left, bounds.top, left + cropWidth, bounds.top + cropHeight),
            ceil(width / 4.0).toInt(), ceil(height / 4.0).toInt())
        if (request != next) {
            close()
            request = next
        }
        if (listener == null && failures < 3 && SystemClock.uptimeMillis() >= nextAt) submit(next)
        return bitmap != null
    }

    private fun submit(input: Request) {
        nextAt = SystemClock.uptimeMillis() + AMBIENT_UPDATE_MS
        val ticket = ++serial
        runCatching {
            val args = captureArgs ?: buildArgs(input).also { captureArgs = it }
            val callback = ObjIntConsumer<Any?> { result, status ->
                val frame = if (status == 0 && result != null) runCatching {
                    val buffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as HardwareBuffer
                    buffer.use {
                        val space = result.javaClass.getMethod("getColorSpace").invoke(result) as? ColorSpace
                        Bitmap.wrapHardwareBuffer(buffer, space)
                    }
                }.getOrNull() else null
                main.post { finish(ticket, frame, "thumbnail status $status") }
            }
            val registered = HiddenApiBypass.newInstance(listenerType, callback)
            listener = registered
            val status = HiddenApiBypass.invoke(captureApi, null, "captureLayers", args, registered) as Int
            check(status == 0) { "thumbnail submission status $status" }
            main.postDelayed(timeout, 1000)
        }.onFailure {
            listener = null
            failures = 3
            failure = it.cause?.toString() ?: it.toString()
        }
    }

    private fun buildArgs(input: Request): Any {
        val builderType = Class.forName(captureApi.name + "\$LayerCaptureArgs\$Builder")
        val baseType = Class.forName(captureApi.name + "\$CaptureArgs\$Builder")
        val builder = HiddenApiBypass.newInstance(builderType, input.control)
        HiddenApiBypass.invoke(builderType, builder, "setChildrenOnly", true)
        HiddenApiBypass.invoke(baseType, builder, "setSourceCrop", input.crop)
        HiddenApiBypass.invoke(baseType, builder, "setFrameScale",
            input.width.toFloat() / input.crop.width(), input.height.toFloat() / input.crop.height())
        HiddenApiBypass.invoke(baseType, builder, "setUid", android.os.Process.myUid().toLong())
        return checkNotNull(HiddenApiBypass.invoke(builderType, builder, "build"))
    }

    private fun finish(ticket: Long, frame: Bitmap?, error: String) {
        if (ticket != serial) return
        ++serial
        main.removeCallbacks(timeout)
        listener = null
        if (frame != null) {
            bitmap = frame
            failure = null
            failures = 0
        } else {
            failure = error
            if (++failures >= 3) bitmap = null
        }
        changed()
    }

    fun close() {
        ++serial
        main.removeCallbacks(timeout)
        listener = null
        request = null
        captureArgs = null
        bitmap = null
        nextAt = 0L
        failures = 0
        failure = null
    }
}
