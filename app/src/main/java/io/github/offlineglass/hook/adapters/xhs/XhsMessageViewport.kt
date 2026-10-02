package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import java.util.WeakHashMap

/** Owns only the message page's native navigation fitting, not list scroll padding. */
internal class XhsMessageViewport {
    private val roots = WeakHashMap<View, Int>()

    fun discover(view: View) {
        if (view.javaClass.name != MESSAGE_PAGE_CLASS) return
        roots.getOrPut(view) { view.paddingBottom }
        clearNavigationPadding(view)
    }

    fun update() {
        for (root in roots.keys) {
            if (root.isAttachedToWindow && root.isShown) clearNavigationPadding(root)
        }
    }

    private fun clearNavigationPadding(root: View) {
        // 9.49.0: o98.h0.r0 -> SystemBarsManager.s -> v5c.r.b reads ROOT
        // navigation insets, then jdc.o.l sets MessagePageView.paddingBottom.
        // Clearing dispatched content insets does not change that root value.
        if (root.paddingBottom != 0) {
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, 0)
        }
    }

    fun restore() {
        for ((root, bottom) in roots) {
            if (root.paddingBottom == 0 && bottom != 0) {
                root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, bottom)
            }
        }
        roots.clear()
    }

    private companion object {
        const val MESSAGE_PAGE_CLASS = "com.xingin.im.v2.message.MessagePageView"
    }
}
