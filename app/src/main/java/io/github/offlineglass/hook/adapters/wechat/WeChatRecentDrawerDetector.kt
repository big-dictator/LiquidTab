package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/** Native recent-page detection; the host owns both visibility animations. */
internal class WeChatRecentDrawerDetector {
    private var panel = WeakReference<View>(null)

    private var lastMissRoot = WeakReference<View>(null)
    private var nextProbeAt = 0L
    private val visibleBounds = Rect()

    fun isVisible(activity: Activity?, minimumVisibleFraction: Float = 0.25f): Boolean =
        visibleHeightFraction(activity) >= minimumVisibleFraction

    fun visibleHeightFraction(activity: Activity?): Float {
        val root = activity?.window?.decorView as? ViewGroup ?: return 0f
        val cached = panel.get()
        if (cached != null && cached.isAttachedToWindow && cached.rootView === root) {
            return visibleHeightFraction(cached, root, visibleBounds)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (lastMissRoot.get() === root && now < nextProbeAt) return 0f
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 4096) {
            val (view, depth) = stack.removeLast()
            if (view.javaClass.name == "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer") {
                nextProbeAt = 0L
                panel = WeakReference(view)
                return visibleHeightFraction(view, root, visibleBounds)
            }
            if (view is ViewGroup && depth < 40) {
                for (index in view.childCount - 1 downTo 0) {
                    stack += view.getChildAt(index) to depth + 1
                }
            }
        }
        if (lastMissRoot.get() !== root) lastMissRoot = WeakReference(root)
        nextProbeAt = now + 48L
        return 0f
    }

    companion object {
        internal fun isExpanded(view: View, root: View, minimumVisibleFraction: Float): Boolean =
            visibleHeightFraction(view, root, Rect()) >= minimumVisibleFraction

        private fun visibleHeightFraction(view: View, root: View, bounds: Rect): Float {
            if (!view.isShown || view.alpha <= 0.01f || root.height <= 0) return 0f
            if (!view.getGlobalVisibleRect(bounds) || bounds.width() < root.width * 0.75f) return 0f
            return bounds.height().toFloat() / root.height
        }
    }
}
