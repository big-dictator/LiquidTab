package io.github.offlineglass.hook.adapters.amap

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassInstaller

/** Amap-only POI floating action bar detection and liquid overlay lifecycle. */
internal object AmapFloatingActionController {
    /** Records the last known Amap floating-action-bar host for this activity. */
    @Synchronized
    internal fun setAmapFloatBar(activity: android.app.Activity, host: android.view.ViewGroup?, source: View?) {
        if (host == null) return
        amapFloatBars[java.lang.System.identityHashCode(activity)] = AmapFloatBarRecord(host, source)
    }

    private data class AmapFloatBarRecord(
        val host: android.view.ViewGroup,
        val source: View?,
    )

    private val amapFloatBars =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Int, AmapFloatBarRecord>())

    /**
     * Amap POI-detail floating action bar → liquid glass.
     *
     * The native bar keeps its icon/text cells and click targets; we strip the
     * semi-transparent chrome (background & any pill outline) and drop an
     * [AmapFloatGlassOverlay] as a *lower* sibling that blurs the live content
     * behind the bar into one wide + two capsule glass buttons. Three zones map
     * to the left four cells and the two right buttons (导航 / 路线) respectively.
     *
     * Geometry is derived at runtime from the child ViewGroup bounds so it stays
     * correct across density/rotation. Returns true when an overlay was installed.
     */
    @Synchronized
    internal fun install(activity: android.app.Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        val decor = activity.window?.decorView as? android.view.ViewGroup ?: return false
        if (decor.width <= 0 || decor.height <= 0) return false

        val bar = findAmapFloatingBar(decor) ?: return false
        val parent = bar.parent as? android.view.ViewGroup ?: return false

        // If we already installed for this exact bar, keep it (no duplicate overlay).
        val existing = GlassInstaller.findViewByClass(parent, AmapFloatGlassOverlay::class.java.name)
        if (existing != null) return true

        val content = findAmapFloatBarContentSource(bar, parent) ?: parent
        val density = activity.resources.displayMetrics.density

        // Absolute screen rect of the bar (used to compute overlay-local zones).
        val barLoc = IntArray(2)
        bar.getLocationInWindow(barLoc)

        val cells = amapFloatBarCells(bar)
        if (cells.size < 2) return false

        val zones = mutableListOf<AmapFloatGlassOverlay.AmapGlassZone>()

        // Leftmost cell block → one wide rounded glass block covering all four
        // left buttons (搜周边/收藏/分享/打车). Remaining cells → capsules.
        val barMidX = barLoc[0] + bar.width / 2
        val leftBlocks = cells.filter { it.left < barMidX }.sortedBy { it.left }
        if (leftBlocks.isNotEmpty()) {
            val lx = leftBlocks.minOf { it.left } - (4f * density).toInt()
            val rx = leftBlocks.maxOf { it.right } + (4f * density).toInt()
            val ty = leftBlocks.minOf { it.top } - (4f * density).toInt()
            val by = leftBlocks.maxOf { it.bottom } + (4f * density).toInt()
            zones += AmapFloatGlassOverlay.AmapGlassZone(
                lx - barLoc[0], ty - barLoc[1], rx - barLoc[0], by - barLoc[1],
                radiusPx = (18f * density).toInt(),
                pad = (6f * density).toInt(),
            )
        }

        // Right two buttons (导航 / 路线) → one capsule each.
        for (cell in cells.filter { it.left >= barMidX }.sortedBy { it.left }) {
            val w = cell.right - cell.left
            val h = cell.bottom - cell.top
            if (h <= 0) continue
            zones += AmapFloatGlassOverlay.AmapGlassZone(
                cell.left - barLoc[0], cell.top - barLoc[1],
                cell.right - barLoc[0], cell.bottom - barLoc[1],
                radiusPx = (h / 2f + 6f * density).toInt(),
                pad = (6f * density).toInt(),
            )
        }

        if (zones.isEmpty()) return false

        // Strip the native chrome (background + reusable pill) so only the
        // icon/text cells stay on top of our glass.
        suppressAmapFloatBarChrome(bar)

        val overlay = AmapFloatGlassOverlay(content, zones.toTypedArray())
        val index = parent.indexOfChild(bar).coerceAtLeast(0)
        parent.addView(overlay, index, android.view.ViewGroup.LayoutParams(bar.width, bar.height))
        overlay.setZones(zones.toTypedArray())

        // Re-locate because addView may change layout offsets.
        overlay.post {
            if (bar.isAttachedToWindow) {
                overlay.update(true)
            }
        }
        setAmapFloatBar(activity, bar, content)
        return true
    }

    private fun findAmapFloatBarContentSource(
        bar: android.view.ViewGroup,
        parent: android.view.ViewGroup,
    ): View? {
        // Prefer a full-window content branch sibling of the bar so the overlay
        // samples the map/list underneath and never re-blurs the bar itself.
        val candidates = GlassInstaller.findFullWindowContentBranch(parent, bar)
        return candidates ?: GlassInstaller.findFullWindowContentBranch(bar.parent as? android.view.ViewGroup ?: parent, bar)
    }

    private fun findAmapFloatingBar(decor: android.view.ViewGroup): android.view.ViewGroup? = runCatching {
        findAmapFloatingBarRec(decor, 0)
    }.getOrNull()

    private fun findAmapFloatingBarRec(view: View, depth: Int): android.view.ViewGroup? {
        if (depth > 14) return null
        val vg = view as? android.view.ViewGroup ?: return null
        // The POI floating action bar: a wide, low strip pinned near the screen
        // bottom whose direct children are a small horizontal cluster of
        // ViewGroups (one left block holding 4 cells + 导航 / 路线). This shape
        // disambiguates it from the taller, many-tab LiteTabBar.
        if (isAmapFloatBarShaped(vg)) return vg
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            if (child is AmapFloatGlassOverlay) continue
            val found = findAmapFloatingBarRec(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun isAmapFloatBarShaped(vg: android.view.ViewGroup): Boolean {
        val decorWidth = vg.rootView.width
        if (decorWidth <= 0) return false
        val w = vg.width
        val h = vg.height
        if (w <= 0 || h <= 0) return false
        val ratio = w.toFloat() / decorWidth
        if (ratio < 0.8f || ratio > 0.98f) return false
        if (h < 80f || h > 320f) return false
        val loc = IntArray(2)
        vg.getLocationOnScreen(loc)
        val context = vg.context
        val screenH = runCatching {
            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                .currentWindowMetrics.bounds.height()
        }.getOrElse { vg.rootView.height }
        if (loc[1] * 1f / screenH < 0.82f) return false
        // Direct children must be a compact horizontal band of ViewGroups —
        // the bar's action cells — not a single wrapper or a sparse container.
        val children = (0 until vg.childCount)
            .mapNotNull { vg.getChildAt(it) as? android.view.ViewGroup }
            .filter { it.visibility == View.VISIBLE && it.width > 20 && it.height > 20 }
        if (children.size < 2 || children.size > 5) return false
        val top = children.minOf { it.top }
        return children.all { kotlin.math.abs(it.top - top) <= h * 0.5f }
    }

    /**
     * The bar's direct ViewGroups, left-to-right. The leftmost cluster becomes
     * one wide glass block and each remaining cell becomes its own capsule,
     * matching the request: 4 left buttons → one block, 导航/路线 → two buttons.
     */
    private fun amapFloatBarCells(bar: android.view.ViewGroup): List<View> =
        (0 until bar.childCount)
            .mapNotNull { bar.getChildAt(it) as? android.view.ViewGroup }
            .filter { it.visibility == View.VISIBLE && it.width > 20 && it.height > 20 }
            .sortedBy { it.left }

    private fun suppressAmapFloatBarChrome(bar: android.view.ViewGroup) {
        var node: View? = bar
        var guard = 0
        while (node != null && guard++ < 6) {
            // Only null the drawable if this exact view isn't also carrying the
            // six tappable cells (would erase icons). The bar itself is just a
            // container; its cells are descendants, so null is safe.
            node.background = null
            node.clipToOutline = false
            node = node.parent as? View
        }
        // Relax clipping so glass overflow isn't cut.
        bar.clipChildren = false
        bar.clipToPadding = false
        bar.elevation = 0f
        (bar.parent as? android.view.ViewGroup)?.let { p ->
            p.clipChildren = false
            p.clipToPadding = false
        }
    }


}
