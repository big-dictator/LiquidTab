package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference

/** Bounded detection for JD's full-screen video channel inside MainFrameActivity. */
internal class JdPageState {
    var fullscreenVideoActive = false
        private set
    var browseIdleTimerActive = false
        private set
    private var lastFullscreenVideoProbeAt = 0L
    private var newProductsRenderer: WeakReference<View>? = null
    private var lastNewProductsProbeAt = 0L
    var newProductsActive = false
        private set
    @Volatile var newProductsSnapshotDirty = true
    @Volatile var contentMotionUntil = 0L
    @Volatile var tabSwitchUntil = 0L

    data class Probe(val active: Boolean, val changed: Boolean)

    fun probeNewProducts(root: ViewGroup, selectedIndex: Int, now: Long): Probe {
        if (selectedIndex != 0) {
            val changed = newProductsActive
            newProductsActive = false
            newProductsRenderer = null
            return Probe(false, changed)
        }
        val cached = newProductsRenderer?.get() as? TextView
        if (cached != null && cached.isAttachedToWindow && cached.isShown &&
            cached.text?.toString()?.trim() == NEW_PRODUCTS_LABEL &&
            (cached.isSelected || cached.contentDescription?.toString()?.contains(SELECTED_DESCRIPTION) == true)
        ) {
            newProductsActive = true
            return Probe(true, false)
        }
        if (now - lastNewProductsProbeAt < NEW_PRODUCTS_PROBE_INTERVAL_MS) {
            return Probe(newProductsActive, false)
        }
        lastNewProductsProbeAt = now
        var found: TextView? = null
        var visited = 0
        val stack = ArrayDeque<View>()
        stack += root
        while (stack.isNotEmpty() && visited++ < NEW_PRODUCTS_PROBE_MAX_VIEWS) {
            val view = stack.removeLast()
            if (view is TextView && view.text?.toString()?.trim() == NEW_PRODUCTS_LABEL &&
                view.isAttachedToWindow && view.isShown && view.alpha > 0.001f &&
                (view.isSelected || view.contentDescription?.toString()?.contains(SELECTED_DESCRIPTION) == true)
            ) {
                found = view
                break
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index)
            }
        }
        val changed = newProductsActive != (found != null)
        newProductsRenderer = found?.let { WeakReference<View>(it) }
        newProductsActive = found != null
        return Probe(newProductsActive, changed)
    }

    fun probeFullscreenVideo(root: ViewGroup?, excludedSurface: View?): Probe {
        val now = SystemClock.uptimeMillis()
        if (now - lastFullscreenVideoProbeAt < 180L || root == null)
            return Probe(fullscreenVideoActive, false)
        lastFullscreenVideoProbeAt = now
        val rootWidth = root.width.coerceAtLeast(1)
        val rootHeight = root.height.coerceAtLeast(1)
        var hasLargeVideoSurface = false
        var hasTopVideoLabel = false
        var hasSelectedVideoLabel = false
        var channelLabelCount = 0
        var visited = 0
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty() && visited++ < 1_600) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE || view.alpha <= 0.01f) continue
            if (view !== excludedSurface && (view is SurfaceView || view is TextureView) &&
                view.width >= rootWidth * 0.72f && view.height >= rootHeight * 0.55f)
                hasLargeVideoSurface = true
            if (view is TextView) {
                val label = view.text?.toString()?.trim().orEmpty()
                if (label in CHANNEL_LABELS) {
                    val location = IntArray(2).also(view::getLocationInWindow)
                    if (location[1] + view.height <= rootHeight * 0.22f) {
                        channelLabelCount++
                        if (label == "视频") {
                            hasTopVideoLabel = true
                            val description = view.contentDescription?.toString().orEmpty()
                            if (view.isSelected || description.contains("已选中")) hasSelectedVideoLabel = true
                        }
                    }
                }
            }
            if (view is ViewGroup && depth < 18) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        val detected = hasLargeVideoSurface && hasTopVideoLabel &&
            (hasSelectedVideoLabel || channelLabelCount >= 3)
        val changed = detected != fullscreenVideoActive
        fullscreenVideoActive = detected
        return Probe(detected, changed)
    }

    fun setBrowseIdleTimerActive(active: Boolean): Boolean {
        if (active == browseIdleTimerActive) return false
        browseIdleTimerActive = active
        return true
    }

    fun reset() {
        fullscreenVideoActive = false
        browseIdleTimerActive = false
        lastFullscreenVideoProbeAt = 0L
        newProductsRenderer = null
        lastNewProductsProbeAt = 0L
        newProductsActive = false
        newProductsSnapshotDirty = true
        contentMotionUntil = 0L
        tabSwitchUntil = 0L
    }

    internal companion object {
        const val CONTENT_MOTION_HOLD_MS = 120L
        const val FLASH_INACTIVE_PROBE_BACKOFF_MS = 5_000L
        const val VIDEO_IDLE_HIDE_DELAY_MS = 1_000L
        val CHANNEL_LABELS = setOf("直播", "推荐", "小说", "短剧", "视频")
        const val NEW_PRODUCTS_LABEL = "新品"
        const val SELECTED_DESCRIPTION = "已选中"
        const val NEW_PRODUCTS_PROBE_INTERVAL_MS = 120L
        const val NEW_PRODUCTS_PROBE_MAX_VIEWS = 320
    }
}
