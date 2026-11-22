package io.github.offlineglass.hook.adapters.meituan

import android.view.View
import android.view.ViewGroup

/** OrderListMPFragment keeps a MachPro viewport shorter than pager_container. */
internal class MeituanTakeoutOrderViewport {
    private data class Entry(val view: View, val height: Int, val measuredHeight: Int)
    private var entries = emptyList<Entry>()
    private var extraHeight = 0
    private var pageHeight = 0
    private var pageWidth = 0
    private val position = IntArray(2)
    private val pagePosition = IntArray(2)

    fun update(binding: MeituanTakeoutBinding, current: MeituanTakeoutBinding.Page?, active: Boolean) {
        if (!active || current == null || current.orderChain.isEmpty()) { release(); return }
        val page = binding.pageContainer
        val source = binding.row
        if (entries.isNotEmpty() && (page.height != pageHeight || page.width != pageWidth ||
                entries.map { it.view } != current.orderChain ||
                entries.any { !it.view.isAttachedToWindow || !it.view.isShown })) release()
        if (entries.isEmpty()) {
            val chain = current.orderChain
            val viewport = chain.first()
            page.getLocationInWindow(pagePosition)
            viewport.getLocationInWindow(position)
            val gap = pagePosition[1] + page.height - position[1] - viewport.height
            // Only the full-width order viewport's native bottom reservation.
            // Header, individual cards, refresh header and other tabs are excluded.
            if (gap !in 1..source.height || viewport.width < page.width * .95f) return
            entries = chain.map { Entry(it, it.height, it.measuredHeight) }
            chain.forEach { MeituanTakeoutOwnership.layout(it) }
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

    fun release() {
        entries = emptyList()
    }
}
