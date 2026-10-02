package io.github.offlineglass.hook.adapters.meituan

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/** OrderListMPFragment keeps a MachPro viewport shorter than pager_container. */
internal class MeituanTakeoutOrderViewport {
    private data class Entry(val view: View, val height: Int, val measuredHeight: Int)
    private var entries = emptyList<Entry>()
    private var extraHeight = 0
    private var pageHeight = 0
    private var pageWidth = 0
    private var nextProbe = 0L
    private val position = IntArray(2)
    private val pagePosition = IntArray(2)

    fun update(source: ViewGroup?, active: Boolean) {
        if (!active || source == null) { restore(); return }
        val id = source.resources.getIdentifier("pager_container", "id", source.context.packageName)
        if (id == 0) return
        val page = source.rootView.findViewById<ViewGroup>(id) ?: return
        if (entries.isNotEmpty() && (page.height != pageHeight || page.width != pageWidth ||
                entries.any { !it.view.isAttachedToWindow || !it.view.isShown })) restore()
        if (entries.isEmpty()) {
            val now = SystemClock.uptimeMillis()
            if (now < nextProbe) return
            nextProbe = now + 250L
            val pager = find(page, "com.sankuai.waimai.machpro.component.viewpager.a", 12) ?: return
            val viewport = pager.parent as? ViewGroup ?: return
            if (viewport.javaClass.name != "com.sankuai.waimai.machpro.component.view.c") return
            page.getLocationInWindow(pagePosition)
            viewport.getLocationInWindow(position)
            val gap = pagePosition[1] + page.height - position[1] - viewport.height
            // Only the full-width order viewport's native bottom reservation.
            // Header, individual cards, refresh header and other tabs are excluded.
            if (gap !in 1..source.height || viewport.width < page.width * .95f) return
            val list = find(pager, "com.sankuai.waimai.machpro.component.list.j", 10) ?: return
            val chain = mutableListOf<View>()
            var node: View? = list
            while (node != null && node !== viewport && chain.size < 12) {
                chain += node
                node = node.parent as? View
            }
            if (node !== viewport) return
            chain += viewport
            entries = chain.asReversed().map { Entry(it, it.height, it.measuredHeight) }
            extraHeight = gap
            pageHeight = page.height
            pageWidth = page.width
        }
        // Run after native layout and before drawing. MachPro may restore its
        // server-specified size; only reapply when a cached viewport differs.
        for (entry in entries) {
            val view = entry.view
            val height = entry.height + extraHeight
            if (view.height == height) continue
            view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(view.left, view.top, view.right, view.top + height)
        }
    }

    fun restore() {
        for (entry in entries) {
            val view = entry.view
            if (!view.isAttachedToWindow || view.height != entry.height + extraHeight) continue
            view.measure(View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(entry.measuredHeight, View.MeasureSpec.EXACTLY))
            view.layout(view.left, view.top, view.right, view.top + entry.height)
            view.requestLayout()
        }
        entries = emptyList()
        nextProbe = 0L
    }

    private fun find(view: View, className: String, depth: Int): View? {
        if (!view.isShown || depth < 0) return null
        if (view.javaClass.name == className) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), className, depth - 1)?.let { return it }
        }
        return null
    }
}
