package io.github.offlineglass.hook

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.util.WeakHashMap

/** Bounded module-work admission window. Never queues native actions or optical frames. */
internal object AnimationResourceGuard {
    private val hosts = WeakHashMap<GlassHostLayout, Long>()
    private val pending = LinkedHashMap<View, () -> Unit>()
    private var scheduled = false
    private val frame = Choreographer.FrameCallback { scheduled = false; sweep() }

    fun begin(host: GlassHostLayout) {
        check(Looper.myLooper() == Looper.getMainLooper())
        hosts[host] = SystemClock.uptimeMillis() + 2000L
        android.os.Trace.beginSection("LiquidTab.AnimationGuard.Begin"); android.os.Trace.endSection()
        schedule()
    }
    fun protecting(view: View): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val now = SystemClock.uptimeMillis()
        return hosts.any { (host, deadline) ->
            deadline > now && host.isAttachedToWindow && host.hasWindowFocus() &&
                host.rootView === view.rootView && host.adapterNavigationAnimationRunning
        }
    }
    /** Same key keeps only latest module work; caller executes normally on false. */
    fun defer(view: View, work: () -> Unit): Boolean {
        if (!protecting(view) || (!pending.containsKey(view) && pending.size >= 64)) return false
        pending[view] = work
        schedule()
        return true
    }
    fun cancel(view: View) { pending.remove(view) }
    fun end(host: GlassHostLayout) { hosts.remove(host); sweep() }
    private fun schedule() {
        if (!scheduled) { scheduled = true; Choreographer.getInstance().postFrameCallback(frame) }
    }
    private fun sweep() {
        val now = SystemClock.uptimeMillis()
        hosts.entries.removeAll { (host, deadline) ->
            deadline <= now || !host.isAttachedToWindow || !host.hasWindowFocus() ||
                !host.adapterNavigationAnimationRunning
        }
        val iterator = pending.entries.iterator()
        val ready = ArrayList<Pair<View, () -> Unit>>()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!protecting(entry.key)) { ready += entry.key to entry.value; iterator.remove() }
        }
        for ((view, work) in ready) {
            if (!view.isAttachedToWindow) continue
            if (!defer(view, work)) {
                android.os.Trace.beginSection("LiquidTab.AnimationGuard.Work")
                try { runCatching(work) } finally { android.os.Trace.endSection() }
            }
        }
        if (hosts.isNotEmpty() || pending.isNotEmpty()) schedule()
    }
}
