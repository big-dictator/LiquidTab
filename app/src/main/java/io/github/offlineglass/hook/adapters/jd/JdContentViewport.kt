package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** JD's native bar shell and content spine are measured independently of the shared glass. */
internal class JdContentViewport {
    private var adjustedSource: WeakReference<ViewGroup>? = null
    private var collapsedBar: WeakReference<ViewGroup>? = null
    private var adjustedRootWidth = 0
    private var adjustedRootHeight = 0
    private var adjustedTab = -1
    private val wantedHeights = WeakHashMap<View, Int>()
    private val barBaseHeights = WeakHashMap<View, Int>()
    private val contentBaseHeights = WeakHashMap<View, Int>()

    fun invalidateAdjustedSource() { adjustedSource = null }
    fun invalidateGeometry() {
        adjustedSource = null
        collapsedBar = null
    }

    fun adjust(
        host: GlassHostLayout,
        source: ViewGroup?,
        root: ViewGroup?,
        newProductsActive: Boolean,
        liftFloatingControls: (ViewGroup, Int) -> Unit,
    ) {
        val nav = source?.takeIf { it.isAttachedToWindow } ?: return
        val scene = root ?: return
        val rootHeight = scene.height
        if (rootHeight <= 0) return
        var bar = nav.parent as? ViewGroup ?: return
        var parent = bar.parent as? ViewGroup ?: return
        while (parent !== scene && parent.height < rootHeight * 0.5f) {
            bar = parent
            parent = parent.parent as? ViewGroup ?: return
        }
        // The 新品 H5 channel continuously remeasures this shell. Do not alternate its height.
        if (newProductsActive) return
        // Apply geometry once for a page/viewport, not whenever JD remeasures
        // its native shell. Fighting those measurements requests another full
        // layout on each pre-draw; the H5 channel avoids precisely this loop.
        if (adjustedSource?.get() === nav && collapsedBar?.get() === bar &&
            adjustedRootWidth == scene.width && adjustedRootHeight == rootHeight &&
            adjustedTab == host.navigationIndex
        ) return
        val barParams = bar.layoutParams ?: return
        adjustedSource = WeakReference(nav)
        collapsedBar = WeakReference(bar)
        adjustedRootWidth = scene.width
        adjustedRootHeight = rootHeight
        adjustedTab = host.navigationIndex
        if (!barBaseHeights.containsKey(bar)) barBaseHeights[bar] = barParams.height
        if (barParams.height != 1) {
            barParams.height = 1
            bar.layoutParams = barParams
        }
        val diag = StringBuilder("JdAdjust t=").append(SystemClock.uptimeMillis())
        diag.append(" barH=").append(bar.height).append(" parentH=").append(parent.height)
        var container: ViewGroup = scene
        for (depth in 0 until 6) {
            var spine: View? = null
            var bestArea = 0L
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                if (child === host || child === bar || child is GlassHostLayout ||
                    child is AdapterOwnedOverlay || child.visibility != View.VISIBLE ||
                    child.width <= 0 || child.height <= 0
                ) continue
                val area = child.width.toLong() * child.height.toLong()
                if (area > bestArea) {
                    bestArea = area
                    spine = child
                }
            }
            val target = spine ?: break
            if (target.height < container.height * 0.5f) break
            val params = target.layoutParams
            if (params != null && params.height > 0 && target.top <= 8 &&
                target.width >= container.width * 0.9f
            ) {
                val wanted = container.height - target.top
                if (wanted > 0 && params.height != wanted &&
                    (target.height < wanted - 4 || wantedHeights.containsKey(target))
                ) {
                    if (!contentBaseHeights.containsKey(target)) contentBaseHeights[target] = params.height
                    params.height = wanted
                    target.layoutParams = params
                    wantedHeights[target] = wanted
                    diag.append(" EXTEND(d").append(depth)
                        .append(" h ").append(target.height).append("->").append(wanted).append(")")
                }
            }
            if (target !is ViewGroup) break
            container = target
        }
        liftFloatingControls(scene, rootHeight)
        Log.i("JdGlassDiag", diag.toString())
    }

    fun restoreForNewProducts() {
        barBaseHeights.forEach { (view, base) ->
            if (!view.isAttachedToWindow) return@forEach
            val params = view.layoutParams ?: return@forEach
            if (params.height != base) { params.height = base; view.layoutParams = params }
        }
        barBaseHeights.clear()
        contentBaseHeights.forEach { (view, base) ->
            if (!view.isAttachedToWindow) return@forEach
            val params = view.layoutParams ?: return@forEach
            if (params.height != base) { params.height = base; view.layoutParams = params }
        }
        contentBaseHeights.clear()
        wantedHeights.clear()
        invalidateGeometry()
    }

    fun restore() {
        invalidateGeometry()
        wantedHeights.clear()
        barBaseHeights.forEach { (view, base) ->
            val params = view.layoutParams ?: return@forEach
            if (params.height != base) { params.height = base; view.layoutParams = params }
        }
        barBaseHeights.clear()
        contentBaseHeights.forEach { (view, base) ->
            val params = view.layoutParams ?: return@forEach
            if (params.height != base) { params.height = base; view.layoutParams = params }
        }
        contentBaseHeights.clear()
    }
}
