package io.github.offlineglass.rendering

import android.view.View
import java.util.WeakHashMap

/** Window-scoped first-visit scheduling. Native routing/optics stay with the caller. */
internal class FirstVisitPageSwitch(
    private val host: View,
    private val owner: Any,
    animating: () -> Boolean,
    onAborted: (Int?) -> Unit,
    private val createPresentation: () -> FirstVisitPresentation? = { null },
) {
    private class History(val count: Int) {
        val visited = HashSet<Int>()
        var lastActual: Int? = null
    }
    companion object {
        // Values contain no window/View references, so history cannot retain an Activity.
        private val windows = WeakHashMap<View, MutableMap<Any, History>>()
    }
    private val barrier = DeferredNativePageSwitch(host, animating)
    private val restore = onAborted
    private var presentation: FirstVisitPresentation? = null
    val waiting: Boolean get() = barrier.waiting
    private fun history(count: Int): History {
        val owners = windows.getOrPut(host.rootView) { HashMap() }
        return owners[owner]?.takeIf { it.count == count }
            ?: History(count).also { owners[owner] = it }
    }
    fun observe(index: Int, count: Int) {
        if (!waiting && count in 2..16 && index in 0 until count) {
            history(count).apply { visited += index; lastActual = index }
        }
    }
    /** Committed-selection renderers can seed their initial page only once. */
    fun seed(index: Int, count: Int) {
        if (!waiting && count in 2..16 && history(count).visited.isEmpty()) observe(index, count)
    }
    fun isCommitted(index: Int, count: Int): Boolean =
        count in 2..16 && index in history(count).visited

    /** New requests always cancel older ones, including requests for native actions. */
    fun defer(index: Int, count: Int, isPage: Boolean, dispatch: () -> Boolean): Boolean {
        runCatching { presentation?.cancel() }; presentation = null
        barrier.cancel(notifyAbort = false)
        if (!isPage || count !in 2..16 || index !in 0 until count) return false
        if (isCommitted(index, count)) {
            android.os.Trace.beginSection("LiquidTab.WarmPage.$index"); android.os.Trace.endSection()
            return false
        }
        val visual = runCatching(createPresentation).getOrNull()
        val accepted = barrier.begin(dispatch = {
            val handled = dispatch()
            runCatching { if (handled) visual?.nativeDispatched(index) else visual?.cancel() }
            handled
        }, onAborted = { runCatching { visual?.cancel() }; restore(history(count).lastActual) })
        if (accepted) {
            presentation = visual
            runCatching { visual?.begin(index) }.onFailure { runCatching { visual?.cancel() } }
            android.os.Trace.beginSection("LiquidTab.FirstVisitWait.$index"); android.os.Trace.endSection()
        }
        return accepted
    }
    // Native success occurs while the barrier is still waiting; unlike observation,
    // this must record it before the callback returns.
    fun dispatched(index: Int, count: Int) {
        if (count in 2..16 && index in 0 until count) {
            history(count).apply { visited += index; lastActual = index }
        }
    }
    fun cancel() { runCatching { presentation?.cancel() }; presentation = null; barrier.cancel() }
    fun dispose() { cancel(); barrier.dispose() }
}
