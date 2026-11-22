package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import android.view.View
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppVideoBackdrop

/** A transition owns pixels, not references to a player being moved/released. */
internal class TikTokBackdropHold {
    var frame: AppVideoBackdrop? = null
        private set
    private var capturedAt = 0L
    private var epoch = 1_000_000
    private companion object {
        // Same platform capability used by the existing native page previews.
        val capture = runCatching {
            HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap", RenderNode::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
        }.getOrNull()
    }
    fun capture(host: GlassHostLayout, now: Long) {
        if (frame != null) return
        val node = host.liveBackdropNodeForAdapter
        if (!node.hasDisplayList() || node.width <= 0 || node.height <= 0) return
        val bitmap = runCatching { capture?.invoke(null, node, node.width, node.height) as? Bitmap }.getOrNull()
            ?: return
        val xy = IntArray(2).also(host::getLocationInWindow)
        val padX = (node.width-host.width)/2
        val padY = (node.height-host.height)/2
        frame = AppVideoBackdrop(bitmap, xy[0]-padX, xy[1]-padY, node.width, node.height, ++epoch)
        capturedAt = now
    }
    fun expire(host: View, now: Long, allowed: Boolean): Boolean {
        if (frame == null || (allowed && host.isAttachedToWindow && now-capturedAt < 1_000L)) return false
        clear()
        return true
    }
    fun clear() { frame = null; capturedAt = 0L } // RenderThread may still reference the bitmap; no eager recycle.
}
