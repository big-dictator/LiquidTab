package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.util.Log
import de.robv.android.xposed.XposedBridge

internal class WeChatRecentDrawerDetector {
    private var lastWeChatRecentProbeAt = 0L
    private var weChatRecentDrawerOpen = false
    private companion object {
        const val PROBE_INTERVAL_MS = 120L
        const val SIDE_SLACK_DP = 32f
        const val MAX_VISITED = 4096
        const val MAX_DEPTH = 24
    }

    fun update(host: View, source: ViewGroup?, density: Float) {

        if (host.width <= 0 || host.height <= 0) return

        val root = host.rootView as? ViewGroup ?: return

        val now = SystemClock.uptimeMillis()

        if (now - lastWeChatRecentProbeAt >= PROBE_INTERVAL_MS) {

            lastWeChatRecentProbeAt = now

            val open = scan(root, host, source, density)

            if (open != weChatRecentDrawerOpen) {

                weChatRecentDrawerOpen = open

                // Entering the pull-down "recent" surface should hide LiquidTab;

                // coming back to the chat list shows it again.

                host.visibility = if (open) View.GONE else View.VISIBLE

                XposedBridge.log(

                    "[OfflineGlass][WeChatRecent] drawer=$open host=${

                        if (open) "hidden" else "shown"

                    }",

                )

            }

        }

    }



    /**

     * WeChat's chat list, when scrolled to the very top and pulled down, can

     * show a "recent" surface as a full-width panel covering the upper screen.

     * Detect that panel by shape: a visible full-width container whose top edge

     * sits in the upper band, tall enough to be a surface rather than a row,

     * and not part of the native tab row. Bleeds the hit view's class into the

     * log so it can be pinned down precisely on first confirmation.

     */

    private fun scan(root: ViewGroup, host: View, source: ViewGroup?, density: Float): Boolean {

        val screenH = root.height

        val screenW = root.width

        if (screenH <= 0 || screenW <= 0) return false

        val topMin = (screenH * 0.20f).toInt()

        val topMax = (screenH * 0.50f).toInt()

        val minH = (screenH * 0.18f).toInt()

        val minW = screenW - (SIDE_SLACK_DP * density).toInt()

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        var visited = 0

        while (stack.isNotEmpty() && visited++ < MAX_VISITED) {

            val (view, depth) = stack.removeLast()

            if (view === host || view.visibility != View.VISIBLE) continue

            val rect = Rect()

            if (view is ViewGroup && view.getGlobalVisibleRect(rect) &&

                rect.width() >= minW && rect.height() >= minH &&

                rect.top in topMin..topMax &&

                !isDescendantOf(view, source) &&

                !isDescendantOf(source ?: host, view) &&

                !isDescendantOf(host, view) &&

                (view.background != null || view.foreground != null || view.elevation > 0f)

            ) {

                android.util.Log.i(

                    "LiquidTab",

                    "wechat recent drawer hit ${view.javaClass.name} rect=$rect",

                )

                return true

            }

            if (view is ViewGroup && depth < MAX_DEPTH) {

                for (index in 0 until view.childCount) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }

        return false

    }

    private fun isDescendantOf(view: View, ancestor: View?): Boolean {
        if (ancestor == null) return false
        var current = view.parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }
}

