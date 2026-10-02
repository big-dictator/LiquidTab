package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Maintains the JD cart's floating back-to-top control above the lifted checkout strip. */
internal class JdBackToTopController {
    private var button: WeakReference<View>? = null
    private val offsets = WeakHashMap<View, Int>()
    private var lastFind = 0L

    fun correct(bar: View?, root: ViewGroup?, finder: JdChromeFinder) {
        val checkout = bar ?: return
        var found = button?.get()
        if (found == null || !found.isAttachedToWindow || found.parent == null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastFind < 200L) return
            lastFind = now
            button = null
            val scene = root ?: return
            if (scene.width <= 0 || scene.height <= 0) return
            found = finder.findBackToTopButton(scene, scene.width, scene.height) ?: return
            button = WeakReference(found)
        }
        val barLocation = IntArray(2).also(checkout::getLocationInWindow)
        val targetBottom = barLocation[1] - BACK_TO_TOP_GAP_PX
        val location = IntArray(2).also(found::getLocationInWindow)
        val delta = location[1] + found.height - targetBottom
        if (abs(delta) <= 4) return
        found.offsetTopAndBottom(-delta)
        offsets[found] = (offsets[found] ?: 0) - delta
    }

    fun restore() {
        offsets.forEach { (view, applied) ->
            if (applied != 0 && view.isAttachedToWindow) view.offsetTopAndBottom(-applied)
        }
        offsets.clear()
        button = null
        lastFind = 0L
    }

    private companion object {
        const val BACK_TO_TOP_GAP_PX = 24
    }
}
