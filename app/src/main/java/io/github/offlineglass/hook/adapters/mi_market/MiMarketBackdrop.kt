package io.github.offlineglass.hook.adapters.mi_market

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Fail closed: replaying a Chromium functor in a second scene can crash RenderThread. */
internal fun isSafeMarketBackdrop(root: View): Boolean {
    if (!root.isAttachedToWindow || !root.isShown) return false
    val pending = ArrayDeque<View>()
    pending.add(root)
    var remaining = 768
    while (pending.isNotEmpty()) {
        if (remaining-- == 0) return false
        when (val view = pending.removeLast()) {
            is WebView, is SurfaceView, is TextureView, is GlassHostLayout, is AdapterOwnedOverlay -> return false
            is ViewGroup -> {
                if (view.childCount > remaining - pending.size) return false
                for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
            }
        }
    }
    return true
}
