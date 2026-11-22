package io.github.offlineglass.rendering

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Reusable motion/commit barrier. Keeps the actual current page, no screenshot. */
internal class DeferredNativePageSwitch(private val host: View, private val animating: () -> Boolean) {
    var waiting = false; private set
    private var generation = 0
    private var shield: View? = null
    private var observer: ViewTreeObserver? = null
    private var commit: Runnable? = null
    private var ready: ViewTreeObserver.OnPreDrawListener? = null
    private var aborted: (() -> Unit)? = null
    fun begin(dispatch: () -> Boolean, onAborted: () -> Unit = {}): Boolean {
        cancel()
        val parent = host.parent as? FrameLayout ?: return false
        if (!host.isHardwareAccelerated || !host.hasWindowFocus()) return false
        val blocker = object : View(host.context), AdapterOwnedOverlay {}
        blocker.isClickable = true
        blocker.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        parent.addView(blocker, parent.indexOfChild(host), FrameLayout.LayoutParams(-1, -1))
        shield = blocker; waiting = true; aborted = onAborted
        val token = generation
        // A lost frame callback or an endless gesture must not strand a shield
        // or dispatch an old page request after the user has left this window.
        host.postDelayed({ if (token == generation && waiting) cancel() }, 2000L)
        fun settled() {
            if (token != generation) return
            if (!host.isAttachedToWindow || !host.hasWindowFocus()) { cancel(); return }
            if (animating()) { host.postOnAnimation { settled() }; return }
            val tree = host.rootView.viewTreeObserver
            observer = tree
            commit = Runnable { host.post {
                if (token != generation || !waiting) return@post
                if (!host.isAttachedToWindow || !host.hasWindowFocus()) { cancel(); return@post }
                if (animating()) { settled(); return@post }
                android.os.Trace.beginSection("LiquidTab.NativeAfterMotion")
                val handled = try { runCatching(dispatch).getOrDefault(false) }
                    finally { android.os.Trace.endSection() }
                if (token != generation) return@post
                if (!handled) { cancel(); return@post }
                waiting = false; aborted = null
                ready = ViewTreeObserver.OnPreDrawListener {
                    ready?.let { if (tree.isAlive) tree.removeOnPreDrawListener(it) }; ready = null
                    commit = Runnable { host.post { if (token == generation) cancel() } }
                    if (tree.isAlive) tree.registerFrameCommitCallback(commit!!)
                    true
                }
                if (tree.isAlive) tree.addOnPreDrawListener(ready)
                host.rootView.postInvalidateOnAnimation()
                host.postDelayed({ if (token == generation) cancel() }, 800L)
            } }
            if (tree.isAlive) tree.registerFrameCommitCallback(commit!!)
            host.postInvalidateOnAnimation()
        }
        host.postOnAnimation { settled() }
        return true
    }
    fun cancel(notifyAbort: Boolean = true) {
        val notify = aborted.takeIf { waiting && notifyAbort }
        aborted = null
        generation++; waiting = false
        ready?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
        commit?.let { if (observer?.isAlive == true) observer?.unregisterFrameCommitCallback(it) }
        ready = null; commit = null; observer = null
        shield?.let { (it.parent as? ViewGroup)?.removeView(it) }; shield = null
        notify?.invoke()
    }
    fun dispose() = cancel()
}
