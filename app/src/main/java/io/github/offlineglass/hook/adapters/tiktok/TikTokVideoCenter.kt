package io.github.offlineglass.hook.adapters.tiktok

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch

/** Apply the physical viewport before the native media view is first measured. */
internal object TikTokVideoCenter {
    private val modal = TikTokPageLayout()

    fun feedViewport(view: View): Pair<Int, Int>? {
        var context = view.context
        var owner: Activity? = null
        for (depth in 0..12) {
            if (context is Activity) { owner = context; break }
            val next = (context as? ContextWrapper)?.baseContext ?: break
            if (next === context) break
            context = next
        }
        val activity = owner ?: return null
        val bounds = activity.windowManager.currentWindowMetrics.bounds
        if (bounds.width() >= bounds.height()) return null
        val root = activity.window.decorView
        if (modal.overlayVisible(root) ||
            (TikTokNativeNavigation.find(root) == null && !hasFeedAncestor(view))) return null
        return bounds.width() to bounds.height()
    }

    private fun hasFeedAncestor(view: View): Boolean {
        var node: View? = view
        for (depth in 0..16) {
            node ?: break
            if (AdapterNavigationSearch.resourceEntryName(node) == "view_rootview") return true
            node = node.parent as? View
        }
        return false
    }

    fun viewport(view: View): Pair<Int, Int>? = feedViewport(view)

    fun changed(view: View) = Unit

    fun prepare(view: View) {
        if (!TikTokPlayerLayout.owns(view) && !TikTokTextureHooks.registered(view) && AdapterNavigationSearch.resourceEntryName(view) != "cover") return
        val viewport = feedViewport(view) ?: return
        var node = view.parent as? View
        while (node != null) {
            val name = AdapterNavigationSearch.resourceEntryName(node)
            if (name == "view_rootview") break
            if (name == "player_view" || name == "video_visible_area_container" || name == "video_container_area") {
                if (name == "video_container_area" && node is android.widget.LinearLayout) {
                    for (i in 0 until node.childCount) {
                        val spacer = node.getChildAt(i)
                        if (spacer.javaClass != View::class.java || spacer.isClickable) continue
                        val p = spacer.layoutParams ?: continue
                        TikTokControlMarginGuard.ownGeometry(spacer, 0, 0, 0f)
                        if (p.height != 0) { p.height = 0; spacer.layoutParams = p }
                    }
                }
                val params = node.layoutParams ?: break
                TikTokControlMarginGuard.ownGeometry(node, ViewGroup.LayoutParams.MATCH_PARENT, 0, 0f)
                var changed = params.height != ViewGroup.LayoutParams.MATCH_PARENT
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                if (params is ViewGroup.MarginLayoutParams && params.bottomMargin != 0) {
                    params.bottomMargin = 0; changed = true
                }
                if (changed) node.layoutParams = params
            }
            node = node.parent as? View
        }
        val original = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (original.width <= 0 || original.height <= 0) return
        val fitted = TikTokPlayerLayout.fit(original, viewport.first, viewport.second,
            TikTokPlayerLayout.tallVideoFill(view))
        if (fitted.width != original.width || fitted.height != original.height || fitted.gravity != original.gravity ||
            original.leftMargin != 0 || original.rightMargin != 0 || original.topMargin != 0 || original.bottomMargin != 0)
            view.layoutParams = fitted
    }
}
