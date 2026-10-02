package io.github.offlineglass.hook.adapters.cainiao

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.OpticalCaptureGeometry
import io.github.offlineglass.hook.adapters.TargetAdapter

internal object CainiaoAdapter : TargetAdapter {
    override val key = "cainiao"
    override val allowsSystemBackgroundBlur = false
    override val usesOpticalSurfacePipeline = true
    override val keepsOpticalSurfaceDuringBarAnimation = true
    override val deferOpticalRenderDuringBarAnimation = true
    override val liveBackdropFramePump = true
    override val requestedFrameRateMax = 120f
    override val forceCompositorFrames = true
    override val stagedNavigationSnapshot = true
    override val redrawSurfaceAfterNavigationCapture = true
    override val observeWindowTouches = true
    override fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        ((host as? GlassHostLayout)?.appNavigationState as? CainiaoNavigationState)?.onWindowTouch(event)
    }
    override fun isOpticalSurfaceTab(selectedIndex: Int): Boolean =
        selectedIndex == 1 || selectedIndex == 2 || selectedIndex == 4
    override fun needsContinuousOpticalSurfaceFrames(selectedIndex: Int): Boolean =
        isOpticalSurfaceTab(selectedIndex)

    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): OpticalCaptureGeometry {
        val rect = Rect(left - padding, top - padding, left + width + padding, top + height + padding)
        // Match the pre-existing software backdrop resolution; no full-window copy.
        val w = (rect.width() / 3f).toInt().coerceAtLeast(1)
        val h = (rect.height() / 3f).toInt().coerceAtLeast(1)
        return OpticalCaptureGeometry(rect, w, h, Rect(
            (padding * w / rect.width().toFloat()).toInt(),
            (padding * h / rect.height().toFloat()).toInt(),
            ((padding + width) * w / rect.width().toFloat()).toInt(),
            ((padding + height) * h / rect.height().toFloat()).toInt()))
    }
    override val navigationImmersionOverride = true
    override fun immersionWasReset(immersiveStub: Any?): Boolean = immersiveStub != null &&
        runCatching { XposedHelpers.callMethod(immersiveStub, "isForceImmersive") as? Boolean }
            .getOrNull() == false
    override fun configureNavigationInsets(decor: View, enabled: Boolean) =
        CainiaoInsets.configure(decor, enabled)
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        CainiaoNavigationState(host)
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        if (navigation.alpha != 0f) navigation.alpha = 0f
        clearChrome(navigation)
        var parent = navigation.parent as? View
        while (parent != null && parent.resourceName() in setOf(
                "navigation_tab_view", "navigation_bar_layout", "ll_navigation_tab_layout")) {
            if (parent.alpha != 0f) parent.alpha = 0f
            clearChrome(parent)
            parent = parent.parent as? View
        }
        navigation.rootView.findNamedView("navigation_tab_view_divider")?.let {
            if (it.alpha != 0f) it.alpha = 0f
            clearChrome(it)
        }
    }
    override fun suppressNativeBottomChrome(host: View, navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)
    private fun clearChrome(view: View) {
        // Called twice per pre-draw: setters must not dirty an already-clean row.
        if (view.background != null) view.background = null
        if (view.backgroundTintList != null) view.backgroundTintList = null
        if (view.foreground != null) view.foreground = null
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
    }
}
internal fun View.resourceName(): String? =
    if (id == View.NO_ID) null else runCatching { resources.getResourceEntryName(id) }.getOrNull()
internal fun View.findNamedView(name: String): View? {
    val resourceId = resources.getIdentifier(name, "id", "com.cainiao.wireless")
    return if (resourceId == 0) null else findViewById(resourceId)
}
