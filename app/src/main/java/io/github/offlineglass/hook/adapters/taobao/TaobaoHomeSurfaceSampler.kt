package io.github.offlineglass.hook.adapters.taobao

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.max
import kotlin.math.roundToInt

/** Homepage compositor sampling, preserving the formal release's coverage and crop rules. */
internal class TaobaoHomeSurfaceSampler {
    private var surface: SurfaceView? = null
    @Volatile private var frame: AppVideoBackdrop? = null
    @Volatile private var scratch: Bitmap? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastProbe = 0L
    private var lastCopy = 0L
    @Volatile private var generation = 0
    @Volatile private var inFlight = false
    private var epoch = 0
    private var currentHost: View? = null
    @Volatile private var allowed = false
    private var fliggy = false
    @Volatile private var frozen = false
    val hasSurface: Boolean get() = surface != null

    fun snapshot(): AppVideoBackdrop? = frame?.takeUnless { it.bitmap.isRecycled }

    fun probe(host: View, home: Boolean) {
        if (!home) {
            changeSurface(null)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastProbe < 120L) return
        lastProbe = now
        val root = host.rootView as? ViewGroup ?: return
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val hostTop = hostLocation[1]
        val hostBottom = hostTop + host.height
        fun coverage(view: SurfaceView): Long {
            val rect = Rect()
            if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0 ||
                !view.holder.surface.isValid || !view.getGlobalVisibleRect(rect) ||
                rect.width() < root.width * 0.6f || rect.height() < root.height * 0.35f ||
                rect.top > hostTop || rect.bottom < hostBottom) return 0L
            return rect.width().toLong() * rect.height()
        }
        surface?.let { if (coverage(it) > 0L) return }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        var best: SurfaceView? = null
        var bestArea = 0L
        while (stack.isNotEmpty() && visited++ < 1600) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            if (view is SurfaceView) {
                val area = coverage(view)
                if (area > bestArea) { best = view; bestArea = area }
            }
            if (view is ViewGroup && depth < 32) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        changeSurface(best)
    }

    private fun changeSurface(next: SurfaceView?) {
        if (surface === next) return
        generation++
        surface = next
        inFlight = false
        lastCopy = 0L
        // A submitted RenderNode can still retain the old bitmap. Release our
        // references; never recycle a published frame from the UI thread.
        frame = null
        scratch = null
        currentHost?.postInvalidateOnAnimation()
    }

    fun request(host: View, pageAllowed: Boolean, fliggyChannel: Boolean, exitFrozen: Boolean) {
        currentHost = host
        allowed = pageAllowed
        fliggy = fliggyChannel
        frozen = exitFrozen
        requestCurrent()
    }

    private fun requestCurrent() {
        val host = currentHost ?: return
        val glass = host as? GlassHostLayout
        if (glass?.config?.solidBarEnabled == true) return
        if (glass != null &&
            (glass.appNavigationState as? TaobaoNavigationState)?.scene?.flashSaleChannelActive == true &&
            glass.barVisibilityAnimating && !glass.barVisibilityTarget) return
        val source = surface ?: return
        if (!allowed || frozen || inFlight || !host.isAttachedToWindow || !host.isShown ||
            host.width <= 0 || host.height <= 0 || !source.holder.surface.isValid) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCopy < 8L) return
        lastCopy = now
        val sourceLocation = IntArray(2).also(source::getLocationInWindow)
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val left = hostLocation[0] - sourceLocation[0]
        val top = hostLocation[1] - sourceLocation[1]
        val pad = (24f * host.resources.displayMetrics.density).roundToInt()
        val horizontalPad = minOf(pad, left.coerceAtLeast(0),
            (source.width - left - host.width).coerceAtLeast(0))
        val topPad = minOf(pad, top.coerceAtLeast(0))
        val bottomPad = minOf(pad, (source.height - top - host.height).coerceAtLeast(0))
        val crop = Rect(left - horizontalPad, top - topPad,
            left + host.width + horizontalPad, top + host.height + bottomPad)
        if (crop.width() <= 0 || crop.height() <= 0) return
        val downscale = if (fliggy) 2.5f else 2f
        val width = max(1, (crop.width() / downscale).roundToInt())
        val height = max(1, (crop.height() / downscale).roundToInt())
        val target = scratch?.takeIf { !it.isRecycled && it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                scratch?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        scratch = null
        val copyHandler = glass?.appNavigationState?.opticalSurfaceCopyHandler() ?: handler
        if (copyHandler == null) {
            worker = HandlerThread("TaobaoHomePixelCopy").apply { start() }
            handler = Handler(worker!!.looper)
        }
        val token = generation
        inFlight = true
        runCatching {
            PixelCopy.request(source, crop, target, { result ->
                    if (token != generation) {
                        target.takeUnless(Bitmap::isRecycled)?.recycle()
                        return@request
                    }
                    inFlight = false
                    if (result == PixelCopy.SUCCESS && allowed && !frozen &&
                        host.isAttachedToWindow && source.isAttachedToWindow && source === surface) {
                        val previous = frame
                        val changed = previous == null || previous.bitmap.isRecycled ||
                            previous.surfaceLeft != sourceLocation[0] + crop.left ||
                            previous.surfaceTop != sourceLocation[1] + crop.top ||
                            previous.surfaceWidth != crop.width() || previous.surfaceHeight != crop.height() ||
                            !previous.bitmap.sameAs(target)
                        if (changed) {
                            scratch = previous?.bitmap
                            frame = AppVideoBackdrop(target, sourceLocation[0] + crop.left,
                                sourceLocation[1] + crop.top, crop.width(), crop.height(), ++epoch)
                        } else scratch = target
                        // Submit this frame immediately through the serial
                        // output queue, without another UI traversal/vsync.
                        if (changed) (host as? GlassHostLayout)?.queueOpticalSurfaceRender()
                    } else {
                        scratch = target
                    }
                    main.post {
                        if (token == generation && allowed && !frozen &&
                            host.isAttachedToWindow && source === surface)
                            host.postOnAnimation { requestCurrent() }
                    }
            }, copyHandler ?: handler!!)
        }.onFailure {
            inFlight = false
            scratch = target
        }
    }

    fun dispose() {
        allowed = false
        changeSurface(null)
        // Invalidate an outstanding callback even if the surface was already cleared.
        generation++
        currentHost = null
        worker?.quitSafely()
        worker = null
        handler = null
        lastProbe = 0L
    }
}
