package io.github.offlineglass.hook.adapters.huawei_health

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState

/**
 * Huawei Health's "今日" text corruption comes from the live backdrop replaying
 * the main layout's RenderNode as a whole: layout_activity_mainui.xml embeds an
 * untagged SurfaceView (the HwBlurEngine render target) plus engine-driven GPU
 * subtrees inside HealthRelativeLayout. Replaying that ancestor paints the
 * engine's compositor artifacts straight over the feed text refracted by the
 * lens.
 *
 * The earlier full-tree split (the YouTube recipe) proved the diagnosis — the
 * text rendered correctly — but its per-frame O(tree) walk caused the scrolling
 * jank. This version keeps the fix while pinning the cost to O(shallow):
 * HealthRelativeLayout is expanded ONE level only (its direct children), the
 * known SurfaceView/TextureView leaves are dropped, and the feed subtree is
 * retained whole. No recursion into feed depth, no per-frame tree walk.
 *
 * Software backdrop capture stays disabled (previous change): the GPU-only
 * card bitmaps software-draw corruptly, and skipping that repaint is free.
 */
internal class HuaweiHealthNavigationState(
    private val host: GlassHostLayout,
) : AppNavigationState {
    override val allowsSoftwareBackdropCapture: Boolean get() = false

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
        val safe = ArrayList<View>(hostIndex + 8)
        for (index in 0 until hostIndex) {
            val child = content.getChildAt(index) ?: continue
            if (child === host || child === host.adapterNavigationSource) continue
            if (child is SurfaceView || child is TextureView) continue
            if (child.visibility != View.VISIBLE || child.alpha <= 0.001f) continue
            if (child.javaClass.name == MAIN_LAYOUT && child is ViewGroup) {
                // Expand the main layout one level: skip the blur engine's
                // untagged SurfaceView (first child in layout_activity_mainui)
                // and the native tab strip; keep the feed pager and the rest.
                for (subIndex in 0 until child.childCount) {
                    val sub = child.getChildAt(subIndex) ?: continue
                    if (sub === host.adapterNavigationSource) continue
                    if (sub is SurfaceView || sub is TextureView) continue
                    if (sub.visibility != View.VISIBLE || sub.alpha <= 0.001f) continue
                    safe += sub
                }
            } else {
                safe += child
            }
        }
        return safe
    }

    private companion object {
        /** Root tag of layout_activity_mainui.xml. */
        private const val MAIN_LAYOUT = "com.huawei.ui.commonui.view.HealthRelativeLayout"
    }
}
