package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Both native cart floating controls stay above the actual liquid checkout row. */
internal class JdBackToTopController {
    private var buttons = emptyList<WeakReference<View>>()
    private val baseTranslations = WeakHashMap<View, Float>()
    private var lastFind = 0L
    private var layoutDiscoveryPending = false
    private val location = IntArray(2)
    private val barLocation = IntArray(2)
    private var currentRoot: WeakReference<ViewGroup>? = null

    fun onNativeLayout() {
        // React may replace one or both controls while keeping the old page
        // attached. A layout event requests discovery before the idle interval.
        // A mount can emit many layouts before its first frame. Preserve the
        // discovery cooldown rather than forcing a full cart-tree walk for each.
        layoutDiscoveryPending = true
    }

    fun correct(bar: View?, root: ViewGroup?, finder: JdChromeFinder, liquidTop: Int? = null) {
        val checkout = bar?.takeIf { it.isShown } ?: return
        val scene = root ?: return
        if (currentRoot?.get() !== scene) {
            restore()
            currentRoot = WeakReference(scene)
        }
        val now = SystemClock.uptimeMillis()
        if (layoutDiscoveryPending || now - lastFind >= 1000L || buttons.isEmpty() ||
            buttons.any { it.get()?.isAttachedToWindow != true || it.get()?.isShown != true }) {
            if (now - lastFind >= 200L) {
                lastFind = now
                layoutDiscoveryPending = false
                buttons = finder.findBackToTopButtons(scene, scene.width, scene.height).map(::WeakReference)
            }
        }
        val gap = (8f * scene.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        checkout.getLocationInWindow(barLocation)
        var targetBottom = (liquidTop ?: barLocation[1]) - gap
        for (reference in buttons.asReversed()) {
            val view = reference.get() ?: continue
            if (!view.isAttachedToWindow || !view.isShown || view.alpha <= .05f) continue
            view.getLocationInWindow(location)
            val delta = location[1] + view.height - targetBottom
            if (abs(delta) > 2) {
                baseTranslations.putIfAbsent(view, view.translationY)
                view.translationY -= delta.toFloat()
            }
            targetBottom -= view.height + gap
        }
    }

    fun restore() {
        baseTranslations.forEach { (view, base) -> if (view.isAttachedToWindow) view.translationY = base }
        baseTranslations.clear()
        buttons = emptyList()
        lastFind = 0L
        layoutDiscoveryPending = false
        currentRoot = null
    }
}
