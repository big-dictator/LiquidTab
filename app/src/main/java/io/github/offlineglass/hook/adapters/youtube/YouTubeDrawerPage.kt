package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import java.util.WeakHashMap

/** Tracks the native Explore pane, including its opening/closing motion. */
internal object YouTubeDrawerPage {
    private data class Probe(var next: Long = 0L, var pane: View? = null, val bounds: Rect = Rect())
    private val probes = WeakHashMap<View, Probe>()

    fun active(root: View): Boolean {
        val probe = probes.getOrPut(root) { Probe() }
        val now = SystemClock.uptimeMillis()
        if (now >= probe.next || probe.pane?.isAttachedToWindow == false) {
            val id = root.resources.getIdentifier("drawer_nav_view", "id", "com.google.android.youtube")
            probe.pane = if (id != 0) root.findViewById(id) else null
            probe.next = now + 1000L
        }
        val pane = probe.pane ?: return false
        return pane.isAttachedToWindow && pane.isShown && pane.alpha > .01f &&
            pane.getGlobalVisibleRect(probe.bounds) && probe.bounds.width() > 1 &&
            probe.bounds.height() > root.height / 3
    }
}
