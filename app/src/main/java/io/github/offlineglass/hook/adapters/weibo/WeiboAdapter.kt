package io.github.offlineglass.hook.adapters.weibo

import android.content.Context
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

internal object WeiboAdapter : TargetAdapter {
    @Volatile internal var currentTab = 0
    @Volatile internal var lastNonVideoTab = 0
    override val key = "weibo"
    override val allowRenderNodeRefresh = true
    override val forceCompositorFrames = true
    override val usesContentDarkMode = true
    override val disablePlatformForceDark = true
    override val liveBackdropFramePump = true
    override val refreshOpticsOnBarShow = true
    override val releaseOutlineClipping = true
    override fun hiddenBarTranslationPx(hostHeight: Int, density: Float) =
        maxOf(hostHeight * .12f, 10f * density)
    override fun scrollStopHideTab(selectedIndex: Int) = selectedIndex == 3
    override fun resetScrollStopHideOnOtherTabs() = true
    override fun isBackPeekTab(selectedIndex: Int) = selectedIndex == 1 || selectedIndex == 3
    override fun isBackPeekActivity(activity: android.app.Activity): Boolean =
        activity.javaClass.name in MAIN_ACTIVITIES
    override val backPeekDurationMs = 1_500L
    override val ownsNavigationDrawing = true
    override fun createNavigationState(context: Context): AppNavigationState = WeiboNavigationState(context)
    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)

    override fun suppressNativeBottomChrome(root: ViewGroup) {
        val shell = root.parent as? ViewGroup
        val tabHost = shell?.parent as? ViewGroup
        tabHost?.let { host ->
            var needsLayout = false
            for (index in 0 until host.childCount) {
                val child = host.getChildAt(index)
                val idName = runCatching { root.resources.getResourceEntryName(child.id) }.getOrDefault("")
                if (child.paddingBottom > 0) {
                    child.setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, 0)
                    needsLayout = true
                }
                if (idName.contains("shadow") || child is ImageView && child.layoutParams.height <= 5) {
                    child.visibility = View.GONE; child.background = null
                    if (child is ImageView) child.setImageDrawable(null)
                }
            }
            if (needsLayout) host.requestLayout()
        }
        shell?.let { container ->
            container.background = null; container.foreground = null
            for (index in 0 until container.childCount) {
                val child = container.getChildAt(index)
                if (child === root) continue
                child.background = null; child.foreground = null
                if (child is ImageView) child.setImageDrawable(null)
            }
        }
        root.background = null; root.foreground = null
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            child.background = null; child.foreground = null; child.backgroundTintList = null
            if (child is ViewGroup) for (inner in 0 until child.childCount) {
                child.getChildAt(inner).apply {
                    background = null; foreground = null; backgroundTintList = null
                }
            }
        }
    }

    private val MAIN_ACTIVITIES = setOf(
        "com.sina.weibo.MainTabActivity",
        "com.sina.weibo.MainActivity",
        "com.sina.weibo.SplashActivity",
    )
}
