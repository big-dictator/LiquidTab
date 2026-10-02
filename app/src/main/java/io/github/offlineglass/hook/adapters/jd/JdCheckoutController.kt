package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Bounded discovery and same-frame positioning of JD's cart checkout strip. */
internal class JdCheckoutController(
    private val finder: JdChromeFinder,
    private val backToTop: JdBackToTopController,
) {
    var bar: WeakReference<View>? = null
    var nextFindAt = 0L
    var discoveryExhausted = false
    var discoveryResetAt = 0L
    private val offsets = WeakHashMap<View, Int>()

    fun onNavigationRebound() {
        discoveryExhausted = false
        nextFindAt = 0L
    }

    fun lift(root: ViewGroup, rootHeight: Int, host: View) {
        val rootWidth = root.width
        if (rootWidth <= 0 || rootHeight <= 0) return
        var current = bar?.get()
        if (current == null || !current.isAttachedToWindow || current.parent == null) {
            bar = null
            if (discoveryExhausted) return
            val now = SystemClock.uptimeMillis()
            if (now < nextFindAt) return
            current = finder.findCheckoutBar(root, rootWidth, rootHeight)
            if (current != null) {
                bar = WeakReference(current)
                nextFindAt = 0L
            } else {
                discoveryExhausted = true
                nextFindAt = Long.MAX_VALUE
            }
        }
        if (current != null) correct(root, host)
    }

    fun correct(root: ViewGroup?, host: View) {
        val current = bar?.get()
        if (current == null || !current.isAttachedToWindow || current.parent == null) {
            bar = null
            if (discoveryExhausted) {
                val now = SystemClock.uptimeMillis()
                if (now >= discoveryResetAt) {
                    discoveryResetAt = now + 300L
                    discoveryExhausted = false
                    nextFindAt = 0L
                }
            }
            return
        }
        val scene = root ?: return
        if (scene.height <= 0 || host.width <= 0 || host.height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationInWindow)
        val safeBottom = hostLocation[1] - CHECKOUT_BOTTOM_GAP_PX
        val barLocation = IntArray(2).also(current::getLocationInWindow)
        val delta = barLocation[1] + current.height - safeBottom
        if (abs(delta) > 6) {
            current.offsetTopAndBottom(-delta)
            offsets[current] = (offsets[current] ?: 0) - delta
        }
        backToTop.correct(current, scene, finder)
    }

    fun restore() {
        offsets.forEach { (view, applied) ->
            if (applied != 0 && view.isAttachedToWindow) view.offsetTopAndBottom(-applied)
        }
        offsets.clear()
        discoveryExhausted = false
    }

    private companion object { const val CHECKOUT_BOTTOM_GAP_PX = 8 }
}
