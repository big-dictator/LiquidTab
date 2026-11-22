package io.github.offlineglass.hook.adapters.meituan

import android.view.View
import android.widget.ScrollView
import io.github.offlineglass.hook.globalGlassBottomGapPx

/** Uses the same verified page binding as navigation, drawing and input. */
internal class MeituanTakeoutProfileSafety {
    private var scroll: ScrollView? = null
    private var base = IntArray(4)
    private var baseClipToPadding = true
    private var appliedBottom = -1
    private val position = IntArray(2)
    private val hostPosition = IntArray(2)

    fun update(host: View, target: ScrollView?, active: Boolean) {
        if (!active || target == null) { release(); return }
        if (scroll !== target) {
            release(); scroll = target
            base = MeituanTakeoutOwnership.padding(target)
            baseClipToPadding = target.clipToPadding
        }
        val view = scroll ?: return
        if (host.height <= 0 || view.height <= 0) return
        host.getLocationInWindow(hostPosition)
        view.getLocationInWindow(position)
        val safeBottom = hostPosition[1] - globalGlassBottomGapPx(host)
        val required = (position[1] + view.height - safeBottom).coerceAtLeast(base[3])
        if (view.paddingBottom != required) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, required)
        }
        appliedBottom = required
        if (view.clipToPadding) view.clipToPadding = false
    }

    fun release() {
        scroll = null
        appliedBottom = -1
    }
}
