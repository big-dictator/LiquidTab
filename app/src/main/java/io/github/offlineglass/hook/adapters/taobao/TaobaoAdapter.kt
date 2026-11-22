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
    private data class ChromeIds(val background: Int,val divider: Int)
    private val chromeIds=java.util.WeakHashMap<android.content.res.Resources,ChromeIds>()
    override val flowingLiquidEnabled = false
    override fun installHooks(
        lpparam: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam,
        spec: io.github.offlineglass.targets.TargetSpec
    ) {
        TaobaoCartInitialResource.install(lpparam.classLoader)
        TaobaoFlashResource.install(lpparam.classLoader)
        TaobaoNativePageTransactions.install(lpparam.classLoader)
        TaobaoSplashHooks.install(lpparam.classLoader)
    }

    override val key = "taobao"
    override fun isNavigationEntryDisabled(index: Int, slots: Int): Boolean = slots == 5 && index == 1
    override val trackSourceDrawableIdentity = true
    override val trackSourceImageBackgroundIdentity = true
    override val allowRenderNodeRefresh = false
    override val ownsNavigationDrawing = true
    override val drawsNavigationInOptics = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override val observeWindowTouches = true
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val stagedNavigationSnapshot = true
    override val usesContentDarkMode = true
    // Homepage channels may be native, UC WebView or independently composed.
    // Read the root buffer behind a separate optical output instead of
    // submitting their retained RenderNodes to a second render pass.
    override val usesOpticalSurfacePipeline = true
    override val allowsSystemBackgroundBlur = false
    override val keepsOpticalSurfaceDuringBarAnimation = true
    // Native routing runs during the spring: its backdrop must advance with it.
    override val keepsBackdropDuringTapTransition = true
    override val retainsOpticalSurfaceWhenBarHidden = true
    override val countsOpticalCompositorCopies = true
    // Share the same full-quality blur input between the panel and its lens.
    override val reuseOpticalBlur = true
    override val reuseSurfaceBlurDuringBarAnimation = true
    // Keep the indicator's own bloom, but do not refract the panel's thin
    // outline a second time through the moving indicator lens.
    override val samplesPanelHighlightInIndicator = false
    // Animation submits through the app-owned worker.
    override val deferOpticalRenderDuringBarAnimation = true
    // Keep one output across tabs: cart entry must not rebuild the compositor.
    override fun isOpticalSurfaceTab(selectedIndex: Int): Boolean = selectedIndex in 0..4 && selectedIndex != 3
    override fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        (host as? GlassHostLayout)?.appNavigationState
            ?.let { it as? TaobaoNavigationState }
            ?.onWindowTouch(host, event)
    }
    override fun scrollStopHideTab(selectedIndex: Int): Boolean = false
    override fun scrollStopHideDelayMs(selectedIndex: Int): Long = if (selectedIndex == 0) 3_000L else 1_000L
    override fun resetScrollStopHideOnOtherTabs(): Boolean = true
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        TaobaoNavigationState(context, host)
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
        val ids=chromeIds.getOrPut(navigation.resources){
            ChromeIds(navigation.resources.getIdentifier("uik_navigation_tab_background","id",navigation.context.packageName),
                navigation.resources.getIdentifier("uik_navigation_tab_divide","id",navigation.context.packageName))
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += rowParent to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if ((ids.background!=0&&view.id==ids.background) || (ids.divider!=0&&view.id==ids.divider)) {
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

}
