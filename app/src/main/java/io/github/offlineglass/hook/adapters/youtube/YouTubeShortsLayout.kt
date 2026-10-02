package io.github.offlineglass.hook.adapters.youtube

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Removes Shorts' reserved footer space without lifting the decoder layer. */
internal class YouTubeShortsLayout {
    private var page: ViewGroup? = null
    private var pageBaseMargin = 0
    private var extension = 0
    private var lastScan = 0L
    private val clips = LinkedHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    private val paddings = LinkedHashMap<View, Int>()
    private val controls = LinkedHashMap<View, ControlBaseline>()
    private val location = IntArray(2)
    private val parentLocation = IntArray(2)

    fun update(host: GlassHostLayout, active: Boolean, recycler: View?) {
        if (!active) {
            restore()
            return
        }
        val content = host.parent as? ViewGroup ?: return
        val found = ancestors(recycler).firstOrNull {
            AdapterNavigationSearch.resourceEntryName(it) == "reel_watch_fragment_root"
        } ?: page?.takeIf { it.isAttachedToWindow && it.isShown }
        if (found == null) return
        if (page !== found) {
            restore()
            page = found
            pageBaseMargin = (found.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            XposedBridge.log("[OfflineGlass][YouTubeLayout] Shorts root=${found.width}x${found.height}")
        }
        val shorts = found
        // MainActivity's rjm.e(int, boolean) reserves the native PivotBar
        // height as pane_fragment_container.paddingBottom (21.39.522).
        // Remove that reservation only while this pane actually owns Shorts.
        var paddingChanged = false
        for (view in ancestors(shorts)) {
            if (view.id == android.R.id.content) break
            if (AdapterNavigationSearch.resourceEntryName(view) == "pane_fragment_container" &&
                view.paddingBottom > 0) {
                paddings[view] = view.paddingBottom
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
                paddingChanged = true
            }
        }
        if (paddingChanged) return // Recompute the remaining system inset after native relayout.
        content.getLocationInWindow(parentLocation)
        shorts.getLocationInWindow(location)
        // Measure the unextended bottom, so the adjustment does not accumulate
        // or alternate between zero and the old footer height after relayout.
        val contentBottom = parentLocation[1] + content.height
        val naturalBottom = location[1] + shorts.height - extension
        val deficit = (contentBottom - naturalBottom).coerceIn(0, (content.height * 0.25f).toInt())
        val params = shorts.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null && (deficit != extension || params.bottomMargin != pageBaseMargin - deficit)) {
            extension = deficit
            params.bottomMargin = pageBaseMargin - extension
            shorts.layoutParams = params
        }
        // Only app-content ancestors are opened. DecorView/window outlines are
        // untouched, preserving freeform-window corners.
        var ancestor: ViewGroup? = shorts
        while (ancestor != null) {
            val group = ancestor
            clips.getOrPut(group) { group.clipChildren to group.clipToPadding }
            group.clipChildren = false
            group.clipToPadding = false
            if (group === content || group.id == android.R.id.content) break
            ancestor = group.parent as? ViewGroup
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScan >= 200L) {
            lastScan = now
            scanControls(shorts)
        }
        // Use the resting layout coordinate. getLocationInWindow(host) includes
        // its exit scale/translation and would make the controls follow it.
        val safeBottom = parentLocation[1] + host.top - (8f * host.resources.displayMetrics.density).roundToInt()
        shorts.getLocationInWindow(location)
        val controlInset = (location[1] + shorts.height - safeBottom)
            .coerceIn(0, shorts.height / 2)
        for ((view, baseline) in controls.toMap()) {
            if (!view.isAttachedToWindow) {
                controls.remove(view)
                continue
            }
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            when (baseline.mode) {
                Mode.CONTROL_LAYER -> {
                    // Full-size interaction layer, excluding the video/snapshot.
                    // Its native bottom-aligned descendants reflow above the bar.
                    // Pager items move vertically during a swipe. Reserve the
                    // same distance in every item; using each item's window Y
                    // would collapse the offscreen next item as it approaches.
                    val margin = maxOf(baseline.margin, controlInset)
                    if (lp.bottomMargin != margin) {
                        lp.bottomMargin = margin
                        view.layoutParams = lp
                    }
                }
                Mode.BOTTOM_CONTROL -> {
                    val expected = baseline.translation + baseline.appliedShift
                    if (abs(view.translationY - expected) > 0.5f) {
                        // Native progress/seek layout may replace its translation.
                        // Treat that as a fresh base instead of subtracting our
                        // old shift from an already reset native coordinate.
                        baseline.translation = view.translationY
                        baseline.appliedShift = 0f
                    }
                    view.getLocationInWindow(location)
                    val unshiftedBottom = location[1] + view.height - baseline.appliedShift
                    val wanted = -(unshiftedBottom - safeBottom).coerceAtLeast(0f)
                    if (abs(view.translationY - (baseline.translation + wanted)) > 0.5f) {
                        baseline.appliedShift = wanted
                        view.translationY = baseline.translation + wanted
                    }
                }
            }
        }
    }

    private fun scanControls(root: ViewGroup) {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            val id = AdapterNavigationSearch.resourceEntryName(view)
            val mode = when (id) {
                "reel_video_interactions", "reel_player_page_content" -> Mode.CONTROL_LAYER
                "reel_progress_bar", "reel_player_footer_container", "time_bar_reference_view",
                "reel_clear_mode_exit_button", "reel_speedmaster_edu_container" -> Mode.BOTTOM_CONTROL
                else -> null
            }
            if (mode != null) {
                val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null) controls.getOrPut(view) {
                    ControlBaseline(lp.bottomMargin, view.translationY, mode)
                }
                // Descendants already move with their full-page control layer.
                if (mode == Mode.CONTROL_LAYER) continue
            }
            if (view is ViewGroup && depth < 28) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
    }

    fun restore() {
        page?.let { view ->
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin == pageBaseMargin - extension) {
                    lp.bottomMargin = pageBaseMargin
                    view.layoutParams = lp
                }
            }
        }
        for ((view, baseline) in controls) {
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.bottomMargin = baseline.margin
                view.layoutParams = lp
            }
            if (baseline.mode == Mode.BOTTOM_CONTROL) view.translationY = baseline.translation
        }
        for ((view, baseline) in clips) {
            view.clipChildren = baseline.first
            view.clipToPadding = baseline.second
        }
        for ((view, bottom) in paddings) {
            if (view.paddingBottom == 0) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
        }
        page = null
        extension = 0
        lastScan = 0L
        controls.clear()
        clips.clear()
        paddings.clear()
    }

    private fun ancestors(view: View?): Sequence<ViewGroup> = sequence {
        var current = view
        var depth = 0
        while (current != null && depth++ < 40) {
            if (current is ViewGroup) yield(current)
            current = current.parent as? View
        }
    }

    private enum class Mode { CONTROL_LAYER, BOTTOM_CONTROL }
    private data class ControlBaseline(val margin: Int, var translation: Float, val mode: Mode,
                                       var appliedShift: Float = 0f)
}
