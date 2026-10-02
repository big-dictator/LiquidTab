package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Douyin 40.3.0: only root_view is an authoritative five-cell navigation row. */
internal object DouyinAdapter : TargetAdapter {
    override fun installHooks(lpparam: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam, spec: TargetSpec) =
        DouyinPlayerTextureHooks.install(lpparam)
    override val key = "douyin"
    override val allowRenderNodeRefresh = true
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val requestedFrameRateMax = 120f
    override val continuousFramePump = true
    override val opticalIconBandScale = 1.5f
    override val secondaryBlurScale = 0.72f
    override val skipBackdropStableDelay = true
    override val forceCompositorFrames = true
    override val restingOpticalMix = 0.86f
    override val hasPerFrameScene = true
    override fun createNavigationState(context: android.content.Context): AppNavigationState =
        DouyinNavigationState(context)
    override fun createNativeChromeController() = object : io.github.offlineglass.hook.adapters.AppNativeChromeController {
        override fun update(host: View, source: ViewGroup?) {
            source ?: return
            suppressNativeBottomChrome(source)
        }
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val row = AdapterNavigationSearch.findFirst(root) { view ->
            AdapterNavigationSearch.resourceEntryName(view) == "root_view" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width >= root.width * 0.9f && view.height > 0 &&
                view.childCount == 5 &&
                (0 until view.childCount).all { view.getChildAt(it).isClickable }
        }
        return row?.let { NavigationCandidate(it, 5, 1_000) }
    }

    /** Retain the native click controller but remove its full-width chrome. */
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        navigation.background = null
        val rowShell = navigation.parent as? ViewGroup ?: return
        rowShell.background = null
        for (index in 0 until rowShell.childCount) {
            val child = rowShell.getChildAt(index)
            if (child !== navigation && child.width >= navigation.width * 0.8f) child.alpha = 0f
        }
        val chrome = rowShell.parent as? ViewGroup ?: return
        chrome.background = null
        for (index in 0 until chrome.childCount) {
            val child = chrome.getChildAt(index)
            if (child !== rowShell && child.width >= navigation.width * 0.8f &&
                child.height >= navigation.height * 0.8f
            ) child.alpha = 0f
        }
        (chrome.parent as? ViewGroup)?.background = null

    }
}
