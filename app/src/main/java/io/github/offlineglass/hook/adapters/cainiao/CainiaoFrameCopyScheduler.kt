package io.github.offlineglass.hook.adapters.cainiao

import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.ceil

/** Read UC's completed app buffer, rather than the preceding frame at pre-draw. */
internal class CainiaoFrameCopyScheduler {
    private var root: View? = null
    private var observer: ViewTreeObserver? = null
    private var committed: Runnable? = null
    private var fallback: Runnable? = null

    fun schedule(view: View, copy: () -> Unit): Boolean {
        if (!view.isHardwareAccelerated || !view.viewTreeObserver.isAlive) return false
        if (committed != null) return true
        root = view
        val tree = view.viewTreeObserver
        observer = tree
        lateinit var submit: Runnable
        submit = Runnable {
            if (committed !== submit) return@Runnable
            cancel()
            if (view.isAttachedToWindow) copy()
        }
        committed = submit
        val timeout = Runnable { submit.run() }
        fallback = timeout
        tree.registerFrameCommitCallback(submit)
        val period = 1000.0 / (view.display?.refreshRate?.takeIf { it > 0f } ?: 60f)
        view.postDelayed(timeout, ceil(period * 2).toLong())
        return true
    }

    fun cancel() {
        committed?.let { if (observer?.isAlive == true) observer?.unregisterFrameCommitCallback(it) }
        fallback?.let { root?.removeCallbacks(it) }
        committed = null
        fallback = null
        observer = null
        root = null
    }
}
