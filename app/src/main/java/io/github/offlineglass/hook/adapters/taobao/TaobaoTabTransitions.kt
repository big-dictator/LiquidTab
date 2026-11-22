package io.github.offlineglass.hook.adapters.taobao

import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout

/** First-visit timing belongs to the common host; native routing stays here. */
internal class TaobaoTabTransitions(private val host: GlassHostLayout,
    private val preview: TaobaoPagePreview) {
    var pendingTarget = -1
        private set
    fun dispatch(source: ViewGroup?, index: Int, count: Int, click: () -> Boolean): Boolean {
        if (source?.isAttachedToWindow != true || index !in 0 until count || count != 5 ||
            !host.hasWindowFocus()) return false
        cancel()
        pendingTarget = index
        android.os.Trace.beginSection("LiquidTab.Taobao.nativeTabClick")
        return try { click() } finally {
            pendingTarget = -1
            android.os.Trace.endSection()
        }
    }
    fun cancel() {
        pendingTarget = -1
        preview.finish()
    }
}
