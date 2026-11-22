package io.github.offlineglass.hook.adapters.youtube

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Search uses the native toolbar and an edge-to-edge result viewport. */
internal object YouTubeSearchPage {
    private data class Probe(var next: Long = 0L, var search: View? = null)
    private val probes = WeakHashMap<View, Probe>()
    fun active(root: View): Boolean {
        val probe = probes.getOrPut(root) { Probe() }
        val now = SystemClock.uptimeMillis()
        if (now >= probe.next || probe.search?.isAttachedToWindow == false) {
            val id = root.resources.getIdentifier("search_layout", "id", "com.google.android.youtube")
            probe.search = if (id != 0) root.findViewById(id) else null
            probe.next = now + 100L
        }
        return probe.search?.let { it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 } == true
    }
}

internal class YouTubeSearchLayout {
    private val padding = WeakHashMap<View, Int>()
    private val bottomMargins = WeakHashMap<View, Int>()
    fun update(root: View, active: Boolean) {
        if (!active) {
            padding.forEach { (view, bottom) ->
                if (view.paddingBottom == 0) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            }
            padding.clear()
            bottomMargins.forEach { (view, bottom) ->
                val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null && params.bottomMargin == 0) {
                    params.bottomMargin = bottom; view.layoutParams = params
                }
            }
            bottomMargins.clear()
            return
        }
        val id = root.resources.getIdentifier("pane_fragment_container", "id", "com.google.android.youtube")
        val pane = if (id != 0) root.findViewById<View>(id) else null
        if (pane != null && pane.paddingBottom > 0) {
            padding.getOrPut(pane) { pane.paddingBottom }
            pane.setPadding(pane.paddingLeft, pane.paddingTop, pane.paddingRight, 0)
        }
        val browseId = root.resources.getIdentifier("browse_fragment_layout_coordinator_layout", "id", "com.google.android.youtube")
        val browse = if (browseId != 0) root.findViewById<View>(browseId) else null
        val params = browse?.layoutParams as? ViewGroup.MarginLayoutParams
        if (browse != null && params != null && params.bottomMargin > 0) {
            bottomMargins.getOrPut(browse) { params.bottomMargin }
            params.bottomMargin = 0; browse.layoutParams = params
        }
    }
}
