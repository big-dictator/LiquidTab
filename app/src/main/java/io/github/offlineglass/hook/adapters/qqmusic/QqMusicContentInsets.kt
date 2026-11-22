package io.github.offlineglass.hook.adapters.qqmusic

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import java.util.WeakHashMap

/**
 * Video and Star retain opaque footer plates and native viewport reservations.
 *
 * Pages, reserve holders, the footer plate and the recycler are located through
 * semantic class anchors and LayoutParams structure — not obfuscated entry names.
 */
internal class QqMusicContentInsets {
    private val margins = WeakHashMap<View, Int>()
    private val paddings = WeakHashMap<ViewGroup, Pair<Int, Boolean>>()
    private val plates = WeakHashMap<View, Int>()
    private var activePage: Int? = null
    private var reservedHeight = 0

    fun update(source: ViewGroup, pageIndex: Int?) {
        if (activePage != pageIndex) restore()
        if (pageIndex == null) {
            restore()
            return
        }
        activePage = pageIndex
        if (margins.isNotEmpty()) {
            applyInsets()
            return
        }
        val root = source.rootView
        val pager = QqMusicBinding.findEntry(root, "main_desk_fragment_pager") as? ViewGroup ?: return
        // The main pager reserve belongs to the old solid navigation on every tab.
        // Internal page corrections below remain specific to Video and Star.
        if (pageIndex != 1 && pageIndex != 3) {
            val params = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            margins.getOrPut(pager) { params.bottomMargin }
            applyInsets()
            return
        }
        val rect = Rect()
        val page = (0 until pager.childCount).map(pager::getChildAt).singleOrNull { child ->
            child.getGlobalVisibleRect(rect) && !rect.isEmpty &&
                AdapterNavigationSearch.findFirst(child) {
                    if (pageIndex == 1) it.javaClass.name == QqMusicBinding.MAIN_CHILD_PAGER_CLASS
                    else it.javaClass.name == QqMusicBinding.DISCOVERY_TAB_CLASS
                } != null
        } ?: return
        val stack = ArrayDeque<View>()
        stack.add(page)
        val targets = ArrayList<View>()
        var recycler: ViewGroup? = null
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            // Reserve holders are full-height content views keeping the old nav margin.
            if (view is ViewGroup && view.javaClass.name == QqMusicBinding.MAIN_CHILD_PAGER_CLASS &&
                view.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT &&
                (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin?.let { it > 0 } == true
            ) {
                targets.add(view)
            }
            // VideoLiveFragment's footer is a bare native View, not the glass host.
            if (pageIndex == 1 && QqMusicBinding.isVideoFooter(view, page)) {
                plates.getOrPut(view) { view.visibility }
                view.visibility = View.INVISIBLE
            }
            if (view.javaClass.name == QqMusicBinding.MUSIC_HALL_RECYCLER_CLASS && view is ViewGroup &&
                view.getGlobalVisibleRect(rect) && !rect.isEmpty
            ) {
                recycler = view
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
        }
        targets.add(pager)
        var reserve = 0
        for (view in targets) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val original = margins.getOrPut(view) { params.bottomMargin }
            reserve += original.coerceAtLeast(0)
            if (params.bottomMargin != 0) {
                params.bottomMargin = 0
                view.layoutParams = params
            }
        }
        reservedHeight = reserve
        recycler?.let { view ->
            paddings.getOrPut(view) { view.paddingBottom to view.clipToPadding }
        }
        applyInsets()
    }

    private fun applyInsets() {
        plates.keys.forEach { it.visibility = View.INVISIBLE }
        margins.keys.forEach { view ->
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                if (it.bottomMargin != 0) {
                    it.bottomMargin = 0
                    view.layoutParams = it
                }
            }
        }
        paddings.forEach { (view, original) ->
            val bottom = original.first + reservedHeight
            if (view.paddingBottom != bottom) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
            view.clipToPadding = false
        }
    }

    fun restore() {
        plates.forEach { (view, visibility) -> view.visibility = visibility }
        plates.clear()
        activePage = null
        margins.forEach { (view, bottom) ->
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                if (it.bottomMargin != bottom) {
                    it.bottomMargin = bottom
                    view.layoutParams = it
                }
            }
        }
        paddings.forEach { (view, original) ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, original.first)
            view.clipToPadding = original.second
        }
        margins.clear()
        paddings.clear()
        reservedHeight = 0
    }
}
