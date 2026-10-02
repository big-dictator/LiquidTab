package io.github.offlineglass.hook.adapters.xianyu

import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.view.WindowInsets

/** Removes only native-navigation reserves from the main pager's page roots. */
internal class XianyuPageChrome {
    private var pager: ViewGroup? = null
    private var nativeRow: View? = null
    private var nativeShell: View? = null
    private var messageTexture: TextureView? = null
    private val location = IntArray(2)
    private val rootLocation = IntArray(2)
    private var homeContainerId = View.NO_ID
    private var homeRefreshId = View.NO_ID

    fun messageFrameTexture(): TextureView? = messageTexture?.takeIf {
        it.isAttachedToWindow && it.isAvailable && it.isShown
    }

    fun prepare(shell: ViewGroup) {
        if (nativeShell !== shell) {
            homeContainerId = shell.resources.getIdentifier("home_container", "id", "com.taobao.idlefish")
            homeRefreshId = shell.resources.getIdentifier("home_swipe_refresh", "id", "com.taobao.idlefish")
        }
        nativeShell = shell
        nativeRow = XianyuNavigationState.find(shell, "indicator_item_container")
        pager = (shell.parent as? ViewGroup)?.let {
            XianyuNavigationState.find(it, "id_pager") as? ViewGroup
        }
        update()
    }

    fun update() {
        val pager = pager ?: return
        val reserve = nativeRow?.height?.takeIf { it > 0 } ?: return
        val root = pager.rootView
        root.getLocationInWindow(rootLocation)
        val screenBottom = rootLocation[1] + root.height
        val gestureReserve = pager.rootWindowInsets
            ?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: 0
        // MainNavigateTabViewPager -> page FrameLayout -> provider content root.
        // Stop before nested feeds, dialogs and page-local bottom controls.
        fun release(view: View, depth: Int) {
            if (view is TextureView &&
                view.javaClass.name == "io.flutter.embedding.android.FlutterTextureView") {
                messageTexture = view
            }
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            // The message page renders its navigation reserve inside Flutter's
            // texture (there is no Android background View to remove). Grow only
            // the main pager's Flutter page viewport by the measured native shell
            // height; the reserve moves below the clipped screen, without scaling
            // the texture or changing the top/content/touch coordinates.
            if (depth == 1 && view.javaClass.name == "io.flutter.embedding.android.FlutterSplashView" &&
                lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT) {
                val extra = nativeShell?.height ?: 0
                if (extra > 0 && lp.bottomMargin != -extra) {
                    lp.bottomMargin = -extra
                    view.layoutParams = lp
                }
            }
            // Personal's provider reserves the same navigation height as root
            // padding instead of a child margin.
            if (depth == 1 && view.paddingBottom == reserve && view.width >= pager.width * .9f) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
            }
            if (depth > 0 && lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                view.width >= pager.width * .9f && lp.bottomMargin == reserve) {
                lp.bottomMargin = 0
                view.layoutParams = lp
            }
            // Refresh can replace the home wrapper with either layout variant.
            // Only extend the named full-page feed boundary, and only by the
            // measured gap within the system navigation inset. Never clear a
            // footer, login prompt, or arbitrary white View.
            val homeBoundary = (homeContainerId > 0 && view.id == homeContainerId) ||
                (homeRefreshId > 0 && view.id == homeRefreshId)
            var enclosingHomeBoundary = false
            var ancestor = view.parent as? View
            while (ancestor != null && ancestor !== pager) {
                if ((homeContainerId > 0 && ancestor.id == homeContainerId) ||
                    (homeRefreshId > 0 && ancestor.id == homeRefreshId)) {
                    enclosingHomeBoundary = true
                    break
                }
                ancestor = ancestor.parent as? View
            }
            if (homeBoundary && !enclosingHomeBoundary && lp != null && lp.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                view.width >= pager.width * .9f && gestureReserve > 0 && view.isLaidOut) {
                view.getLocationInWindow(location)
                val baseBottom = location[1] + view.height + lp.bottomMargin
                val gap = screenBottom - baseBottom
                if (gap in 1..gestureReserve && lp.bottomMargin != -gap) {
                    lp.bottomMargin = -gap
                    view.layoutParams = lp
                }
            }
            if (view is ViewGroup && depth < 3) for (i in 0 until view.childCount) {
                release(view.getChildAt(i), depth + 1)
            }
        }
        release(pager, 0)
    }

    fun clear() { pager = null; nativeRow = null; nativeShell = null; messageTexture = null }
}
