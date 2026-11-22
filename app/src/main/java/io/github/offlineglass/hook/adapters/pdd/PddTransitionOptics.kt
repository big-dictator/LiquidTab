package io.github.offlineglass.hook.adapters.pdd

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.SystemClock
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.RetainedNavigationBackdrop
import java.util.concurrent.Executors

/** One GPU-backed strip for the floating bar while the promotion scene binds. */
internal class PddTransitionOptics {
    private data class Strip(
        val backdrop: RetainedNavigationBackdrop,
        val hostWidth: Int,
        val hostHeight: Int,
        val rootWidth: Int,
        val rootHeight: Int,
    )

    private var strip: Strip? = null
    private var capturePending = false
    private var generation = 0
    private var retryAt = 0L

    fun prepare(host: GlassHostLayout, stillOnSource: () -> Boolean) {
        val config = host.config
        if (!config.enabled || !config.liquidGlassEnabled || !config.backdropCapture ||
            config.solidBarEnabled || !host.isAttachedToWindow || !host.isShown || !host.hasWindowFocus()
        ) {
            clear()
            return
        }
        if (capturePending || backdrop(host) != null || SystemClock.uptimeMillis() < retryAt) return
        val scene = host.liveBackdropNodeForAdapter
        val width = scene.width
        val height = scene.height
        val hostWidth = host.width
        val hostHeight = host.height
        if (!scene.hasDisplayList() || !host.hasUsableBackdrop() || hostWidth <= 0 || hostHeight <= 0 ||
            width < hostWidth || height < hostHeight || width.toLong() * height > MAX_PIXELS
        ) return
        val copy = createHardwareBitmap ?: return
        val rootWidth = host.rootView.width
        val rootHeight = host.rootView.height
        val screen = IntArray(2).also(host::getLocationOnScreen)
        val transform = Matrix().apply {
            setTranslate(screen[0] - (width - hostWidth) * .5f, screen[1] - (height - hostHeight) * .5f)
        }
        val epoch = generation
        capturePending = true
        retryAt = SystemClock.uptimeMillis() + RETRY_MS
        worker.execute {
            val bitmap = runCatching { copy.invoke(null, scene, width, height) as? Bitmap }.getOrNull()
            host.post {
                capturePending = false
                if (epoch != generation || !stillOnSource() || !host.isAttachedToWindow ||
                    !host.hasWindowFocus() || bitmap?.config != Bitmap.Config.HARDWARE ||
                    host.width != hostWidth || host.height != hostHeight ||
                    host.rootView.width != rootWidth || host.rootView.height != rootHeight
                ) return@post
                val node = RenderNode("LiquidTab promotion transition strip")
                node.setPosition(0, 0, width, height)
                val canvas = node.beginRecording()
                canvas.drawBitmap(bitmap, 0f, 0f, null)
                node.endRecording()
                strip = Strip(RetainedNavigationBackdrop(node, transform), hostWidth, hostHeight, rootWidth, rootHeight)
            }
        }
    }

    fun backdrop(host: GlassHostLayout): RetainedNavigationBackdrop? = strip?.takeIf {
        it.hostWidth == host.width && it.hostHeight == host.height &&
            it.rootWidth == host.rootView.width && it.rootHeight == host.rootView.height
    }?.backdrop

    fun clear() {
        if (strip != null || capturePending) generation++
        strip = null
    }

    private companion object {
        const val MAX_PIXELS = 1_500_000L
        const val RETRY_MS = 250L
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-Pdd-Optics").apply { isDaemon = true }
        }
        val createHardwareBitmap by lazy {
            runCatching {
                HardwareRenderer::class.java.getDeclaredMethod(
                    "createHardwareBitmap", RenderNode::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
