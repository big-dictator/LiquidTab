package io.github.offlineglass.hook.adapters.taobao

import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.ceil

/** Read the submitted native frame, never queue a readback ahead of its draw. */
internal class TaobaoFrameCopyScheduler {
    private var root: View? = null
    private var observer: ViewTreeObserver? = null
    private var pending: Runnable? = null
    private var timeout: Runnable? = null

    fun schedule(view: View, copy: () -> Unit): Boolean {
        if (!view.isHardwareAccelerated || !view.viewTreeObserver.isAlive) return false
        if (pending != null) return true
        root = view
        observer = view.viewTreeObserver
        lateinit var submit: Runnable
        submit = Runnable {
            if (pending !== submit) return@Runnable
            cancel()
            if (view.isAttachedToWindow && view.hasWindowFocus()) copy()
        }
        pending = submit
        observer?.registerFrameCommitCallback(submit)
        // An unchanged native root need not redraw just to sample its buffer.
        timeout = Runnable { submit.run() }.also {
            val period = 1000.0 / (view.display?.refreshRate?.takeIf { rate -> rate > 0f } ?: 60f)
            view.postDelayed(it, ceil(period * 2).toLong())
        }
        return true
    }

    fun cancel() {
        pending?.let { if (observer?.isAlive == true) observer?.unregisterFrameCommitCallback(it) }
        timeout?.let { root?.removeCallbacks(it) }
        pending = null
        timeout = null
        observer = null
        root = null
    }
}
