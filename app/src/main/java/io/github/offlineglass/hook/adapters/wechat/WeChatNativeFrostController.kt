package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Repairs only LauncherUI page FrostedContentView instances. */
internal class WeChatNativeFrostController {
    private var loggedRepair = false
    private var cachedPager = java.lang.ref.WeakReference<ViewGroup>(null)

    private fun isItemContainer(view: View): Boolean {
        if (view is android.widget.AbsListView) return true
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "androidx.recyclerview.widget.RecyclerView") return true
            type = type.superclass
        }
        return false
    }

    private companion object {
        const val FROSTED_CONTENT_VIEW_CLASS = "com.tencent.mm.ui.FrostedContentView"
        const val BOUNCE_VIEW_CLASS = "com.tencent.mm.ui.widget.pulldown.MMWeUIBounceView"
    }

    fun suppress(source: ViewGroup?, hostRoot: View, selected: Int) {
        source ?: return
        if (source.visibility != View.VISIBLE) source.visibility = View.VISIBLE
        if (source.alpha != 0f) source.alpha = 0f

        val pager = findMainViewPager(hostRoot) ?: return
        removeWeChatNativeTabInset(pager, source.height)
        for (index in 0 until pager.childCount) {
            val page = pager.getChildAt(index)
            if (page.javaClass.name != FROSTED_CONTENT_VIEW_CLASS) continue
            WeChatContactsLayoutRepair.repair(page)
            val oldHeight = runCatching {
                XposedHelpers.callMethod(page, "getBottomBlurAreaHeight") as? Int
            }.getOrNull()
            val oldColor = runCatching {
                XposedHelpers.callMethod(page, "getFrostedColor") as? Int
            }.getOrNull()
            val wasEnabled = runCatching {
                XposedHelpers.callMethod(page, "getFrostedEnabled") as? Boolean
            }.getOrNull()
            val oldTop = runCatching { XposedHelpers.callMethod(page, "getTopBlurAreaHeight") as? Int }.getOrNull()
            if (oldHeight != 0 || oldColor != 0 || wasEnabled == true || oldTop != 0) {
                clearNativeFrost(page)
                page.invalidate()
                (page.parent as? View)?.invalidate()
                hostRoot.invalidate()
                if (!loggedRepair) {
                    loggedRepair = true
                    XposedBridge.log("[OfflineGlass][WeChatFrost] page=${page.javaClass.name} bottom=$oldHeight -> 0 selected=$selected")
                }
            }
        }
    }

    private fun findMainViewPager(root: View): ViewGroup? {
        cachedPager.get()?.takeIf { it.isAttachedToWindow && it.rootView === root.rootView }?.let { return it }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 384) {
            val (view, depth) = stack.removeLast()
            if (WeChatLauncherPages.isMainPager(view)) {
                cachedPager = java.lang.ref.WeakReference(view as ViewGroup)
                return view
            }
            if (!isItemContainer(view) && view is ViewGroup && depth < 18) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
            }
        }
        return null
    }

    private fun clearNativeFrost(page: View) {
        runCatching { XposedHelpers.callMethod(page, "setFrostedEnabled", false) }
        runCatching { XposedHelpers.callMethod(page, "setFrostedColor", 0) }
        runCatching { XposedHelpers.callMethod(page, "setBottomBlurAreaHeight", 0) }
        runCatching { XposedHelpers.callMethod(page, "setTopBlurAreaHeight", 0) }
    }

    private fun removeWeChatNativeTabInset(pager: ViewGroup, nativeTabHeight: Int) {
        if (nativeTabHeight <= 0) return
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += pager to 0
        while (stack.isNotEmpty()) {
            val (candidate, depth) = stack.removeLast()
            if (candidate.javaClass.name == BOUNCE_VIEW_CLASS) {
                val margins = candidate.layoutParams as? ViewGroup.MarginLayoutParams
                if (margins != null && margins.bottomMargin in 1..(nativeTabHeight + 8)) {
                    margins.bottomMargin = 0
                    candidate.layoutParams = margins
                    candidate.requestLayout()
                }
            }
            if (!isItemContainer(candidate) && candidate is ViewGroup && depth < 6) {
                for (i in 0 until candidate.childCount) stack += candidate.getChildAt(i) to (depth + 1)
            }
        }
    }
}
