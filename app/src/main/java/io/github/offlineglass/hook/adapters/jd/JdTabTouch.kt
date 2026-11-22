package io.github.offlineglass.hook.adapters.jd

import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout

/** Dispatch JD's original tab action directly from the user's selection. */
internal class JdTabTouch(private val host: GlassHostLayout, private val preview: JdPagePreview) {
    var pendingTarget = -1
        private set
    fun selected(source: ViewGroup?, count: Int): Int? {
        return JdNavigationBinding.resolve(source)?.takeIf { it.slots.size == count }?.selected()
    }

    fun dispatch(source: ViewGroup?, index: Int, count: Int): Boolean {
        val row = JdNavigationBinding.resolve(source)?.takeIf { it.slots.size == count } ?: return false
        if (index == JdNavigationBinding.BROWSE) return false
        val touch = row.slots.getOrNull(index)?.touch ?: return false
        if (!touch.isAttachedToWindow || !touch.isShown || touch.windowToken !== host.windowToken ||
            !touch.isEnabled || !host.hasWindowFocus()) return false
        cancel()
        pendingTarget = index
        android.os.Trace.beginSection("LiquidTab.JD.nativeTabClick")
        var accepted = false
        try {
            JdNativePageTransitions.click(host) {
                JdNavigationClickGate.click { accepted = touch.performClick() }
            }
        } finally {
            pendingTarget = -1
            android.os.Trace.endSection()
        }
        // Never retry a business click or mark a native cooldown rejection visited.
        return accepted && selected(source, count) == index
    }
    fun cancel() {
        pendingTarget = -1
        if (preview.target >= 0) preview.finish()
        preview.nativeWillDispatch()
    }
}
