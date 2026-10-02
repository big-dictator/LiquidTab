package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import de.robv.android.xposed.XposedBridge

/** Per-host target finder for WeChat native navigation colour repairs. */
internal class WeChatContentTargetProbe {
    fun resolveTarget(host: View, root: ViewGroup, foldBar: View?, foldProxy: View?, veil: WeChatVeilController): View? {

        var candidateTarget = target

        if (candidateTarget != null && (!candidateTarget.isAttachedToWindow || candidateTarget.width <= 0 ||

                candidateTarget.visibility != View.VISIBLE)

        ) {

            veil.clearContacts()

            clear()

            candidateTarget = null

        }

        if (candidateTarget != null) return candidateTarget

        val now = SystemClock.uptimeMillis()

        if (now - lastTargetProbe < 250L) return null

        lastTargetProbe = now



        val content = host.parent as? ViewGroup ?: return null

        var best: View? = null

        var bestArea = 0

        var bestWithFold: View? = null

        var bestWithFoldArea = 0

        for (index in 0 until content.childCount) {

            val child = content.getChildAt(index)

            if (child === host || child === foldProxy) continue

            if (child.visibility != View.VISIBLE || child.alpha < 0.5f) continue

            val rect = Rect()

            if (!child.getGlobalVisibleRect(rect)) continue

            // The root view spans its window at the origin, so the on-screen

            // area is the intersection with the root bounds.

            if (!rect.intersect(0, 0, root.width, root.height)) continue

            val area = rect.width() * rect.height()

            if (foldBar != null && isDescendantOf(foldBar, child) && area > bestWithFoldArea) {

                bestWithFoldArea = area

                bestWithFold = child

            }

            if (area > bestArea) {

                bestArea = area

                best = child

            }

        }

        val chosen = bestWithFold ?: best

        target = chosen

        if (chosen != null) {

            XposedBridge.log(

                "[OfflineGlass][WeChatContentTarget] target FOUND=${chosen.javaClass.name} " +

                    "size=${chosen.width}x${chosen.height}",

            )

        }

        return chosen

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

    var target: View? = null
    var appliedSelected = -1
    var lastTargetProbe = 0L
    fun clear() {
        target = null
    }
}
