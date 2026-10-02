package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import android.widget.ScrollView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.util.WeakHashMap

/** Cart geometry and native tab artwork belong exclusively to Meituan main. */
internal class MeituanMainNavigationState : AppNavigationState {
    private val lifted = WeakHashMap<View, Float>()
    private var nextScan = 0L
    private var cachedFooter: View? = null
    private var cachedTargets = emptyList<View>()
    private val position = IntArray(2)

    private fun walk(root: View, visit: (View) -> Unit) {
        if (root is GlassHostLayout || root.visibility != View.VISIBLE) return
        visit(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), visit)
    }

    private fun restore() {
        lifted.forEach { (view, translation) -> view.translationY = translation }
        lifted.clear()
        cachedFooter = null
        cachedTargets = emptyList()
    }

    override fun onNavigationTabSwitched(index: Int, slotCount: Int, now: Long) {
        restore()
        nextScan = 0L
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        source ?: return
        // Native selected state remains available even when the source is transparent.
        if (currentSelection != 3) { restore(); return }
        val now = SystemClock.uptimeMillis()
        host.getLocationInWindow(position)
        val barTop = position[1].toFloat()
        val density = host.resources.displayMetrics.density
        if (now >= nextScan || cachedFooter?.isAttachedToWindow != true || cachedFooter?.isShown != true) {
        nextScan = now + 250L
        val targets = linkedSetOf<View>()
        walk(host.rootView) { view ->
            if (view is TextView && view.text.toString().startsWith("结算")) {
                var ancestor = view.parent as? View
                var footer: View? = null
                while (ancestor != null && ancestor !== host.rootView) {
                    if (ancestor.width >= host.rootView.width * .8f &&
                        ancestor.height in 1..(100 * density).toInt()) footer = ancestor
                    if (ancestor.height > 100 * density) break
                    ancestor = ancestor.parent as? View
                }
                footer?.let(targets::add)
            }
            val description = view.contentDescription?.toString().orEmpty()
            val id = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
            if (description.contains("回到顶部") || description.contains("返回顶部") || description == "置顶" ||
                id.contains("back_top") || id.contains("back_to_top")) {
                targets += view
            }
        }
        // MSC's floating return control can be an unlabelled image. Limit the
        // geometry fallback to the cart root's non-scrolling overlay branch.
        val footer = targets.firstOrNull { it.width >= host.rootView.width * .8f }
        val page = footer?.parent as? ViewGroup
        if (page != null) walk(page) { view ->
            if (view !is ViewGroup || view.width !in 1..(80 * density).toInt() ||
                view.height !in 1..(80 * density).toInt()) return@walk
            var ancestor = view.parent as? View
            var excluded = false
            while (ancestor != null && ancestor !== page) {
                if (ancestor === footer || ancestor is ScrollView ||
                    ancestor.javaClass.name.contains("Scroll") || ancestor.javaClass.name.contains("Recycler")) excluded = true
                ancestor = ancestor.parent as? View
            }
            if (excluded) return@walk
            view.getLocationInWindow(position)
            if (position[0] < host.rootView.width * .7f || position[1] < host.rootView.height * .65f) return@walk
            var image = false
            walk(view) { if (it is ImageView) image = true }
            if (image) targets += view
        }
        cachedFooter = footer
        cachedTargets = targets.filter { candidate ->
            var parent = candidate.parent as? View
            var nested = false
            while (parent != null) {
                if (parent in targets) nested = true
                parent = parent.parent as? View
            }
            !nested
        }
        lifted.keys.toList().filter { it !in cachedTargets }.forEach { view ->
            lifted.remove(view)?.let { view.translationY = it }
        }
        }
        val lift = cachedFooter?.let { view ->
            val baseline = lifted[view] ?: view.translationY
            view.getLocationInWindow(position)
            val bottom = position[1] + view.height - (view.translationY - baseline)
            (bottom - barTop + 4 * density).coerceAtLeast(0f)
        } ?: 0f
        // Enforce the cached geometry on every pre-draw, including frames in
        // which MSC resets translation while updating selection or detail controls.
        for (view in cachedTargets) {
            val baseline = lifted.getOrPut(view) { view.translationY }
            view.translationY = baseline - lift
        }
    }

    private val tabs = MeituanMainTabsRenderer()
    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        source ?: return
        tabs.prepareFirstFrame(source)
    }
    override fun onNavigationSnapshotReady(snapshot: android.graphics.Bitmap, slotCount: Int) {
        tabs.accept(snapshot, slotCount)
    }
    override fun drawFixedNavigationSlot(canvas: Canvas, index: Int, selectedIndex: Int,
        dark: Boolean, centerX: Float, centerY: Float, boxSize: Float): Boolean {
        val source = drawingSource ?: return false
        val host = drawingHost ?: return false
        val interactionScale = boxSize / minOf(source.width / source.childCount.toFloat(), source.height.toFloat())
        return tabs.draw(canvas, index, centerX, host.height / 2f, interactionScale)
    }
    private var drawingSource: ViewGroup? = null
    private var drawingHost: View? = null
    private var currentSelection = -1
    private var loggedSource: ViewGroup? = null
    override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float, barHeightPx: Int, surfaceColor: () -> Int) {
        drawingSource = source
        drawingHost = host
        currentSelection = selected()
    }
    override fun dispose() { restore(); tabs.clear(); drawingSource = null; drawingHost = null }
}


