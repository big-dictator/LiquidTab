package io.github.offlineglass.hook.adapters.taobao

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNativeChromeController
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import android.content.Context

/** Hide Taobao's separate full-width background/divider, retaining TabWidget as click adapter. */
internal object TaobaoAdapter : TargetAdapter {
    override val key = "taobao"
    override val trackSourceDrawableIdentity = true
    override val allowRenderNodeRefresh = true
    override val observeWindowTouches = true
    override val ownsNavigationTap = true
    override val stagedNavigationSnapshot = true
    override val usesContentDarkMode = true
    // Homepage channels may be native, UC WebView or independently composed.
    // Read the root buffer behind a separate optical output instead of
    // submitting their retained RenderNodes to a second render pass.
    override val usesOpticalSurfacePipeline = true
    override val allowsSystemBackgroundBlur = false
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val retainsOpticalSurfaceWhenBarHidden = true
    override val countsOpticalCompositorCopies = true
    // Share the same full-quality blur input between the panel and its lens.
    override val reuseOpticalBlur = true
    // Animation submits through the app-owned worker, rather than also drawing
    // from PixelCopy while the UI thread is submitting the same Surface.
    override val deferOpticalRenderDuringBarAnimation = true
    override fun isOpticalSurfaceTab(selectedIndex: Int): Boolean = selectedIndex == 0 || selectedIndex == 3
    override fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        (host as? GlassHostLayout)?.appNavigationState
            ?.let { it as? TaobaoNavigationState }
            ?.onWindowTouch(host, event)
    }
    override fun scrollStopHideTab(selectedIndex: Int): Boolean = selectedIndex == 2
    override fun scrollStopHideDelayMs(selectedIndex: Int): Long = if (selectedIndex == 0) 3_000L else 1_000L
    override fun resetScrollStopHideOnOtherTabs(): Boolean = true
    override fun createNavigationState(context: Context): AppNavigationState = TaobaoNavigationState(context)
    override fun createNativeChromeController(): AppNativeChromeController = object : AppNativeChromeController {
        override fun update(host: View, source: ViewGroup?) {
            ((host as? GlassHostLayout)?.appNavigationState as? TaobaoNavigationState)
                ?.updatePageChrome(host, source)
        }
    }

    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        suppressChrome(navigation, extendContent = true)
    }

    override fun suppressNativeBottomChrome(host: View, navigation: ViewGroup) {
        // The app owns dynamic page measurements. Rewriting its content spine
        // from every pre-draw races channel creation and repeatedly requests layout.
        suppressChrome(navigation, extendContent = false)
    }

    private fun suppressChrome(navigation: ViewGroup, extendContent: Boolean) {
        clearBackground(navigation)
        val rowParent = navigation.parent as? ViewGroup ?: return
        clearBackground(rowParent)
        if (rowParent.clipChildren) rowParent.clipChildren = false
        if (rowParent.clipToPadding) rowParent.clipToPadding = false
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += rowParent to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            val id = resourceEntryName(view)
            if (id == "uik_navigation_tab_background" || id == "uik_navigation_tab_divide") {
                clearBackground(view)
                if (view.alpha != 0f) view.alpha = 0f
            }
            if (view is ViewGroup && depth < 4) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        val tabHost = rowParent.parent as? ViewGroup
        if (tabHost?.background != null) tabHost.background = null
        if (tabHost?.backgroundTintList != null) tabHost.backgroundTintList = null
        if (tabHost?.foreground != null) tabHost.foreground = null
        if (tabHost != null && tabHost.alpha != 0f) tabHost.alpha = 0f
        if (extendContent) extendContentThroughNativeInset(navigation)
    }

    /** Replacing an already transparent drawable on every preDraw dirties the
     * entire page again and defeats the compositor's idle-frame backoff. Still
     * inspect every frame so a native tab background change is cleared before
     * it can become visible. Android's changed setters perform invalidation. */
    private fun clearBackground(view: View) {
        val background = view.background
        if (background !is ColorDrawable || Color.alpha(background.color) != 0 || background.alpha != 0) {
            view.background = ColorDrawable(Color.TRANSPARENT)
        }
        if (view.backgroundTintList != null) view.backgroundTintList = null
    }

    private fun extendContentThroughNativeInset(navigation: ViewGroup) {
        val root = navigation.rootView as? ViewGroup ?: return
        val contentId = runCatching {
            navigation.resources.getIdentifier("tbTabFragment", "id", navigation.context.packageName)
        }.getOrDefault(0)
        if (contentId == 0) return
        var current: View = root.findViewById<View>(contentId) ?: return
        repeat(8) {
            val parent = current.parent as? ViewGroup ?: return
            val params = current.layoutParams
            var changed = false
            if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (params is ViewGroup.MarginLayoutParams && params.bottomMargin != 0) {
                params.bottomMargin = 0
                changed = true
            }
            if (changed) {
                current.layoutParams = params
                current.requestLayout()
                parent.requestLayout()
            }
            if (parent.height >= root.height * 0.95f || parent === navigation.parent) return
            current = parent
        }
    }

    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

}
