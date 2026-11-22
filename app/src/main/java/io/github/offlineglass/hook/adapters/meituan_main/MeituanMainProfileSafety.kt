package io.github.offlineglass.hook.adapters.meituan_main

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import io.github.offlineglass.hook.globalGlassBottomGapPx

/** The mine MSC page owns a full-width vertical scroll viewport. */
internal class MeituanMainProfileSafety(private val binding: MeituanMainBinding) {
    private var scroll: ScrollView? = null
    private var originalBottom = 0
    private var originalClip = true
    private var appliedBottom = -1
    private var nextProbe = 0L
    private val hostPosition = IntArray(2)
    private val scrollPosition = IntArray(2)
    private data class Viewport(val view: View, val height: Int, val measured: Int, var applied: Int = -1)
    private var viewports = emptyList<Viewport>()

    private fun extendViewport(view: ScrollView, host: View) {
        val wrapper = view.parent as? ViewGroup ?: return
        val owner = wrapper.parent as? ViewGroup ?: return
        if (!binding.isMsc(owner) || !wrapper.javaClass.name.startsWith("com.meituan.msc.mmpviews.scroll.") ||
            wrapper.top != 0 || view.top != 0 || wrapper.width < owner.width * .9f) return
        val base = viewports.firstOrNull()?.height ?: wrapper.height
        val extra = owner.height - base
        // Only remove the verified bottom navigation reservation, not headers
        // or arbitrary short scroll panels embedded inside the page.
        if (extra !in 1..(host.rootView.height * .08f).toInt()) return
        if (viewports.isEmpty()) viewports = listOf(
            Viewport(wrapper, wrapper.height, wrapper.measuredHeight),
            Viewport(view, view.height, view.measuredHeight))
        for (entry in viewports) {
            val target = entry.height + extra
            if (entry.view.height != target) {
                entry.view.measure(View.MeasureSpec.makeMeasureSpec(entry.view.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.EXACTLY))
                entry.view.layout(entry.view.left, entry.view.top, entry.view.right, entry.view.top + target)
            }
            entry.applied = target
        }
    }

    fun update(host: View, active: Boolean) {
        if (!active) { restore(); return }
        if (scroll?.let { !it.isAttachedToWindow || !it.isShown } == true) restore()
        if (scroll == null) {
            val now = SystemClock.uptimeMillis()
            if (now < nextProbe) return
            nextProbe = now + 250L
            scroll = binding.scroll(host.rootView)
            scroll?.let { originalBottom = it.paddingBottom; originalClip = it.clipToPadding }
        }

        val view = scroll ?: return
        if (host.height <= 0 || view.height <= 0) return
        extendViewport(view, host)
        host.getLocationInWindow(hostPosition)
        view.getLocationInWindow(scrollPosition)
        // Ignore the host's entry/exit translation: safe space must not grow
        // and shrink with the bar animation. Insets still update the geometry.
        val safeBottom = hostPosition[1] - host.translationY - globalGlassBottomGapPx(host)
        val required = (scrollPosition[1] + view.height - safeBottom).toInt().coerceAtLeast(originalBottom)
        if (view.paddingBottom != required) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, required)
        }
        appliedBottom = required
        if (view.clipToPadding) view.clipToPadding = false
    }

    fun restore() {
        scroll?.let { view ->
            if (view.isAttachedToWindow) {
                if (view.paddingBottom == appliedBottom) {
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, originalBottom)
                }
                view.clipToPadding = originalClip
            }
        }
        scroll = null
        for (entry in viewports) if (entry.view.isAttachedToWindow && entry.view.height == entry.applied) {
            entry.view.measure(View.MeasureSpec.makeMeasureSpec(entry.view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(entry.measured, View.MeasureSpec.EXACTLY))
            entry.view.layout(entry.view.left, entry.view.top, entry.view.right, entry.view.top + entry.height)
            entry.view.requestLayout()
        }
        viewports = emptyList()
        appliedBottom = -1
        nextProbe = 0L
    }
}
