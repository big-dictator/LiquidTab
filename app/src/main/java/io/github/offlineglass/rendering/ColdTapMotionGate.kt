package io.github.offlineglass.rendering

import android.os.Trace
import android.view.View
import android.view.ViewTreeObserver
/** Defers native cold-tab work until the settled navigation frame is submitted. */
internal class ColdTapMotionGate(private val host: View) {
    private var pending: (() -> Unit)? = null
    private var timeout: Runnable? = null
    private var observer: ViewTreeObserver? = null
    private var commit: Runnable? = null
    private var generation = 0
    val waiting: Boolean get() = pending != null

    fun begin(timeoutMs: Long, dispatch: () -> Unit): Boolean {
        cancel()
        if (!host.isAttachedToWindow || !host.isShown || !host.hasWindowFocus() ||
            !host.isHardwareAccelerated || !host.viewTreeObserver.isAlive) return false
        val token = generation
        pending = dispatch
        timeout = Runnable { finish(token, "Timeout") }.also { host.postDelayed(it, timeoutMs) }
        host.postInvalidateOnAnimation()
        return true
    }

    fun onSettledFrameDrawn() {
        if (!waiting || commit != null) return
        val tree = host.viewTreeObserver.takeIf { it.isAlive } ?: return
        val token = generation
        val callback = Runnable { host.post { finish(token, "Commit") } }
        observer = tree
        commit = callback
        tree.registerFrameCommitCallback(callback)
    }

    private fun finish(token: Int, reason: String) {
        if (token != generation) return
        val dispatch = pending ?: return
        cancel()
        if (!host.isAttachedToWindow || !host.hasWindowFocus()) return
        Trace.beginSection("LiquidTab.NativeAfterColdTapMotion.$reason")
        try { dispatch() } finally { Trace.endSection() }
    }

    fun cancel() {
        generation++
        pending = null
        timeout?.let(host::removeCallbacks)
        commit?.let { callback ->
            observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
        }
        timeout = null
        commit = null
        observer = null
    }
}
