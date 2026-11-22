package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout

/** Own the checkout only while its native controls are bound. */
internal class JdCheckoutController(
    private val finder: JdChromeFinder,
    private val backToTop: JdBackToTopController,
    private val isCartActive: () -> Boolean,
) {
    private val checkoutFinder = JdCheckoutBinding.Finder()
    private var binding: JdCheckoutBinding? = null
    private var sourceAlpha = 1f
    private var liquid: JdCartLiquidButtons? = null
    private var nextFindAt = 0L
    private var visiblePage = -1
    private var previewScene: View? = null

    fun setPreviewScene(scene: View?) {
        if (previewScene === scene) return
        previewScene = scene
        liquid?.invalidate()
    }

    fun onCartLayout(root: ViewGroup, host: View) {
        backToTop.onNativeLayout()
        lift(root, root.height, host)
    }

    fun syncPageVisibility(index: Int) {
        if (visiblePage != index) {
            visiblePage = index
            if (index == 3) nextFindAt = 0L
        }
        liquid?.let {
            val wanted = if (index == 3) View.VISIBLE else View.INVISIBLE
            if (it.visibility != wanted) it.visibility = wanted
        }
    }

    fun onNavigationRebound() {
        releaseBinding()
        nextFindAt = 0L
    }

    fun lift(root: ViewGroup, rootHeight: Int, host: View) {
        if (root.width > 0 && rootHeight > 0) correct(root, host)
    }

    fun correct(root: ViewGroup?, host: View) {
        val glass = host as? GlassHostLayout ?: return
        if (!isCartActive() || root == null || glass.width <= 0 || glass.height <= 0) return
        syncPageVisibility(3)
        var current = binding
        if (current == null || !current.isCurrent()) {
            releaseBinding()
            val now = SystemClock.uptimeMillis()
            if (now < nextFindAt) return
            nextFindAt = now + 120L
            current = checkoutFinder.find(glass) ?: return
            val parent = glass.parent as? FrameLayout ?: return
            val overlay = JdCartLiquidButtons(glass.context, glass, current) {
                previewScene ?: binding?.eventRoot?.takeIf { it.isAttachedToWindow && it.isShown }
            }
            parent.addView(overlay, parent.indexOfChild(glass), FrameLayout.LayoutParams(-1,
                (88f * parent.resources.displayMetrics.density).toInt()))
            binding = current
            sourceAlpha = current.shell.alpha
            liquid = overlay
        }
        if (current.shell.alpha != 0f) {
            sourceAlpha = current.shell.alpha
            current.shell.alpha = 0f
        }
        liquid?.let {
            it.refresh()
            backToTop.correct(current.shell, current.eventRoot, finder, it.capsuleTopInWindow())
        }
    }

    private fun releaseBinding() {
        liquid?.let { it.release(); (it.parent as? ViewGroup)?.removeView(it) }
        liquid = null
        binding?.shell?.let { if (it.alpha == 0f) it.alpha = sourceAlpha }
        binding = null
    }

    fun restore() {
        previewScene = null
        releaseBinding()
        nextFindAt = 0L
        visiblePage = -1
    }
}
