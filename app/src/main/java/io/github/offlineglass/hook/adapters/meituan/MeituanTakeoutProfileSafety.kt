package io.github.offlineglass.hook.adapters.meituan

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import io.github.offlineglass.hook.globalGlassBottomGapPx

/** MineMPFragment's obfuscated ScrollView is missed by legacy name matching. */
internal class MeituanTakeoutProfileSafety {
    private var scroll: ScrollView? = null
    private var base = IntArray(4)
    private var baseClipToPadding = true
    private var appliedBottom = -1
    private var nextProbe = 0L
    private val position = IntArray(2)
    private val hostPosition = IntArray(2)

    fun update(host: View, active: Boolean) {
        if (!active) { restore(); return }
        if (scroll?.let { !it.isAttachedToWindow || !it.isShown } == true) restore()
        if (scroll == null) {
            val now = SystemClock.uptimeMillis()
            if (now < nextProbe) return
            nextProbe = now + 250L
            val id = host.resources.getIdentifier("pager_container", "id", host.context.packageName)
            if (id == 0) return
            val page = host.rootView.findViewById<ViewGroup>(id) ?: return
            val found = find(page, page.width, 12) ?: return
            scroll = found
            base = intArrayOf(found.paddingLeft, found.paddingTop, found.paddingRight, found.paddingBottom)
            baseClipToPadding = found.clipToPadding
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

    private fun find(view: View, width: Int, depth: Int): ScrollView? {
        if (!view.isShown || depth < 0) return null
        if (view is ScrollView &&
            view.javaClass.name == "com.sankuai.waimai.machpro.component.scroll.c" &&
            view.width >= width * .95f && view.height >= view.rootView.height * .5f) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), width, depth - 1)?.let { return it }
        }
        return null
    }

    fun restore() {
        scroll?.let { view ->
            if (view.isAttachedToWindow) {
                // Do not overwrite a newer native padding value on page replacement.
                if (view.paddingBottom == appliedBottom) {
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, base[3])
                }
                view.clipToPadding = baseClipToPadding
            }
        }
        scroll = null
        appliedBottom = -1
        nextProbe = 0L
    }
}
