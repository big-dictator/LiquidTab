package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.Rect
import android.view.TextureView
import android.view.View
import java.util.WeakHashMap

/** Observe the actual native player view instead of binding a renamed factory. */
internal object TikTokTextureHooks {
    private val textures = java.util.Collections.synchronizedMap(WeakHashMap<TextureView, Boolean>())
    var gpuActive = false
        private set
    private val position = IntArray(2)
    private val bar = Rect()
    private val rect = Rect()

    fun registered(view: View) = view is TextureView && textures.containsKey(view)
    fun observe(view: TextureView) { textures[view] = true }

    fun active(host: View): TextureView? = synchronized(textures) {
        host.getLocationOnScreen(position)
        bar.set(position[0], position[1], position[0]+host.width, position[1]+host.height)
        var best: TextureView? = null
        var bestOverlap = -1L
        var bestVisibleArea = -1L
        for (texture in textures.keys) {
            if (!texture.isAttachedToWindow || !texture.isShown || !texture.isAvailable ||
                runCatching { (texture.surfaceTexture?.timestamp ?: 0L) <= 0L }.getOrDefault(true) ||
                texture.rootView !== host.rootView || !texture.getGlobalVisibleRect(rect)) continue
            val visibleArea = rect.width().toLong()*rect.height()
            val overlap = if (rect.intersect(bar)) rect.width().toLong()*rect.height() else 0L
            // Select decoded pixels actually behind this host. A neighbouring
            // texture elsewhere on screen must not suppress a live Surface
            // or the native photo scene during a feed transition.
            if (overlap > 0L && (overlap > bestOverlap || (overlap == bestOverlap && visibleArea > bestVisibleArea))) {
                best = texture; bestOverlap = overlap; bestVisibleArea = visibleArea
            }
        }
        gpuActive = best != null
        best
    }

}
