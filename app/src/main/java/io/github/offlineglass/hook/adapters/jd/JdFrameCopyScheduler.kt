package io.github.offlineglass.hook.adapters.jd

import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.ceil

/** Submit readback after the app frame, rather than ahead of its RenderThread sync. */
internal class JdFrameCopyScheduler {
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
        // Static roots may not submit any frame. Do not force another full
        // traversal just for readback; preserve the existing idle probe loop.
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
