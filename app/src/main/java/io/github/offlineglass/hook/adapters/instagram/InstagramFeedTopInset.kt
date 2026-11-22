package io.github.offlineglass.hook.adapters.instagram

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout

/** Extends only the verified home feed viewport; native toolbars keep their safe-area layout. */
internal class InstagramFeedTopInset {
    private var source: ViewGroup? = null
    private var binding: Binding? = null
    private var nextDiscoveryAt = 0L
    private var lastFailure: String? = null

    fun prepare(navigation: ViewGroup?) {
        if (source === navigation) return
        clear()
        source = navigation
    }

    fun update() {
        val navigation = source ?: return
        if (!navigation.isAttachedToWindow) {
            clear()
            return
        }
        var current = binding
        if (current != null && !current.valid(navigation)) {
            current.restore()
            binding = null
            current = null
            nextDiscoveryAt = 0L
        }
        if (current == null && SystemClock.uptimeMillis() >= nextDiscoveryAt) {
            nextDiscoveryAt = SystemClock.uptimeMillis() + 5_000L
            current = discover(navigation)
            binding = current
        }
        current ?: return
        val insets = navigation.rootWindowInsets ?: return
        val topInset = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
        val panelPosition = current.panelPosition.also(current.panel::getLocationInWindow)
        // The current native panel starts below the status bar. Never compensate another offset.
        if (!current.feedTab.isSelected || !current.list.isShown || topInset <= 0 ||
            kotlin.math.abs(panelPosition[1] - topInset) > 1) {
            current.restore()
            return
        }
        current.apply(topInset)
    }

    fun clear() {
        binding?.restore()
        binding = null
        source = null
        nextDiscoveryAt = 0L
    }

    private fun discover(navigation: ViewGroup): Binding? {
        val feedTabs = (0 until navigation.childCount).map(navigation::getChildAt)
            .filter { it.entryName() == "feed_tab" && it.isClickable }
        val feedTab = feedTabs.singleOrNull() ?: return unavailable("feed_tab clickable candidate count=${feedTabs.size}")
        val ancestors = ArrayList<ViewGroup>()
        var parent = navigation.parent as? ViewGroup
        while (parent != null && ancestors.size < 6) {
            ancestors += parent
            if (parent.entryName() == "layout_container_main_panel") break
            parent = parent.parent as? ViewGroup
        }
        val panel = ancestors.lastOrNull()?.takeIf { it.entryName() == "layout_container_main_panel" }
            ?: return unavailable("main panel ancestor missing")
        if (ancestors.size != 2 || ancestors.firstOrNull()?.entryName() != "layout_container_main_wrapper")
            return unavailable("tab bar wrapper mismatch")

        val candidates = ArrayList<Binding>()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += panel to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 2_000) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && view.id == android.R.id.list &&
                (view.parent as? FrameLayout)?.entryName() == "refreshable_container") {
                val chain = ArrayList<ViewGroup>()
                var node = view.parent as? ViewGroup
                while (node != null && chain.size < 12) {
                    chain += node
                    if (node === panel) break
                    node = node.parent as? ViewGroup
                }
                val sticky = chain.firstOrNull { it.entryName() == "sticky_header_list" }
                val page = sticky?.parent as? ViewGroup
                val params = view.layoutParams as? FrameLayout.LayoutParams
                if (chain.lastOrNull() === panel && page != null && params != null &&
                    params.height == ViewGroup.LayoutParams.MATCH_PARENT && params.topMargin == 0 &&
                    view.top == 0 && hasUniqueFeedHeader(page)) {
                    candidates += Binding(panel, view, feedTab, chain)
                }
            }
            if (view is ViewGroup && depth < 14) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        // An exhausted traversal cannot establish uniqueness.
        if (stack.isNotEmpty()) return unavailable("feed discovery exceeded node budget")
        val result = candidates.singleOrNull()
            ?: return unavailable("feed list/header/FrameLayout contract candidate count=${candidates.size}")
        lastFailure = null
        return result
    }

    private fun unavailable(reason: String): Binding? {
        if (lastFailure != reason) android.util.Log.i("LiquidTabInstagram", "feed top inset unchanged: $reason")
        lastFailure = reason
        return null
    }

    private fun hasUniqueFeedHeader(page: ViewGroup): Boolean {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += page to 0
        var visited = 0
        var count = 0
        while (stack.isNotEmpty() && visited++ < 256) {
            val (view, depth) = stack.removeLast()
            if (view.entryName() == "main_feed_action_bar") count++
            if (view is ViewGroup && depth < 4) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return stack.isEmpty() && count == 1
    }

    private class Binding(
        val panel: ViewGroup,
        val list: ViewGroup,
        val feedTab: View,
        val chain: List<ViewGroup>,
    ) {
        val panelPosition = IntArray(2)
        private var appliedInset = 0
        private var originalPaddingTop = 0
        private var originalClipToPadding = true
        private var originalTopMargin = 0
        private var ancestorClips = emptyList<Pair<ViewGroup, Boolean>>()

        fun valid(navigation: ViewGroup): Boolean {
            if (!list.isAttachedToWindow || feedTab.parent !== navigation || list.parent !== chain.first()) return false
            for (index in 0 until chain.lastIndex) {
                if (chain[index].parent !== chain[index + 1]) return false
            }
            return navigation.parent?.parent === panel
        }

        fun apply(inset: Int) {
            if (appliedInset == inset) {
                val params = list.layoutParams as? FrameLayout.LayoutParams ?: return
                // A native rebind may reassert its own margin/padding without replacing the list.
                if (params.topMargin == originalTopMargin) {
                    params.topMargin = -inset
                    list.layoutParams = params
                }
                if (list.paddingTop != originalPaddingTop + inset) {
                    originalPaddingTop = list.paddingTop
                    list.setPadding(list.paddingLeft, originalPaddingTop + inset, list.paddingRight, list.paddingBottom)
                }
                return
            }
            restore()
            val params = list.layoutParams as? FrameLayout.LayoutParams ?: return
            // Native layout owns these values outside our extension. Snapshot on each activation.
            originalPaddingTop = list.paddingTop
            originalClipToPadding = list.clipToPadding
            originalTopMargin = params.topMargin
            if (originalTopMargin != 0) return
            ancestorClips = chain.map { it to it.clipChildren }
            appliedInset = inset
            // Keep initial content at its native position, but let scrolled items traverse the safe area.
            // A negative margin increases a MATCH_PARENT FrameLayout child's measured viewport too.
            params.topMargin = -inset
            list.layoutParams = params
            list.setPadding(list.paddingLeft, originalPaddingTop + inset, list.paddingRight, list.paddingBottom)
            list.clipToPadding = false
            ancestorClips.forEach { (view, _) -> view.clipChildren = false }
        }

        fun restore() {
            if (appliedInset == 0) return
            val params = list.layoutParams as? FrameLayout.LayoutParams
            if (params != null && params.topMargin == -appliedInset) {
                params.topMargin = originalTopMargin
                list.layoutParams = params
            }
            if (list.paddingTop == originalPaddingTop + appliedInset) {
                list.setPadding(list.paddingLeft, originalPaddingTop, list.paddingRight, list.paddingBottom)
            }
            list.clipToPadding = originalClipToPadding
            ancestorClips.forEach { (view, clip) -> view.clipChildren = clip }
            ancestorClips = emptyList()
            appliedInset = 0
        }
    }
}

private fun View.entryName(): String? = runCatching { resources.getResourceEntryName(id) }.getOrNull()
