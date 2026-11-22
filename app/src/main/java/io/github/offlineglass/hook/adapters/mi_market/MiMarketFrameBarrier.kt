package io.github.offlineglass.hook.adapters.mi_market

import android.view.View

/** The first readback of a new scene must follow removal of window-drawn glass. */
internal class MiMarketFrameBarrier {
    private var committed = Long.MIN_VALUE
    private var pendingEpoch = Long.MIN_VALUE
    private var pendingRoot: View? = null
    private var observer: android.view.ViewTreeObserver? = null
    private var commit: Runnable? = null
    private var fallback: Runnable? = null

    fun reset() {
        cancel()
        committed = Long.MIN_VALUE
    }

    private fun cancel() {
        registeredCallback?.let { if (observer?.isAlive == true) observer?.unregisterFrameCommitCallback(it) }
        fallback?.let { pendingRoot?.removeCallbacks(it) }
        commit = null; registeredCallback = null; fallback = null; observer = null; pendingRoot = null
        pendingEpoch = Long.MIN_VALUE
    }

    fun schedule(root: View, epoch: Long, currentEpoch: () -> Long, copy: () -> Unit): Boolean {
        if (committed == epoch) return false
        if (commit != null) {
            if (pendingRoot === root && pendingEpoch == epoch) return true
            cancel()
        }
        if (!root.isAttachedToWindow || !root.isHardwareAccelerated || !root.viewTreeObserver.isAlive) return true
        pendingRoot = root
        pendingEpoch = epoch
        observer = root.viewTreeObserver
        lateinit var submit: Runnable
        submit = Runnable {
            if (commit !== submit) return@Runnable
            cancel()
            if (root.isAttachedToWindow && currentEpoch() == epoch) {
                committed = epoch
                copy()
            }
        }
        // Always publish on UI, after the current frame callback has returned.
        val callback = Runnable { root.post(submit) }
        commit = submit
        // Keep the registered callback separately so cancellation also unregisters it.
        registeredCallback = callback
        observer?.registerFrameCommitCallback(callback)
        val period = 1000.0 / (root.display?.refreshRate?.takeIf { it > 0f } ?: 60f)
        fallback = submit
        root.postDelayed(submit, kotlin.math.ceil(period * 3).toLong().coerceAtLeast(32L))
        root.postInvalidateOnAnimation()
        return true
    }
    private var registeredCallback: Runnable? = null
}
