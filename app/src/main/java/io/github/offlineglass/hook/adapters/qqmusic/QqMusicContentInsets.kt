package io.github.offlineglass.hook.adapters.qqmusic

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import java.util.WeakHashMap

/** Video and Star retain opaque footer plates and native viewport reservations. */
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
        val pagerId = QqMusicNativeChrome.id(root, "main_desk_fragment_pager")
        val pager = root.findViewById<ViewGroup>(pagerId) ?: return
        // The main pager reserve belongs to the old solid navigation on every tab.
        // Internal page corrections below remain specific to Video and Star.
        if (pageIndex != 1 && pageIndex != 3) {
            val params = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            margins.getOrPut(pager) { params.bottomMargin }
            applyInsets()
            return
        }
        val rect = Rect()
        val page = (0 until pager.childCount).map(pager::getChildAt).firstOrNull { child ->
            child.getGlobalVisibleRect(rect) && !rect.isEmpty &&
                AdapterNavigationSearch.findFirst(child) {
                    if (pageIndex == 1) AdapterNavigationSearch.resourceEntryName(it) == "fck" &&
                        it.javaClass.name.endsWith(".MainDesckChildViewPager")
                    else it.javaClass.name == "com.tencent.qqmusic.ui.DiscoveryDeskTitleTabViewNew"
                } != null
        } ?: return
        val stack = ArrayDeque<View>()
        stack.add(page)
        val targets = ArrayList<View>()
        var recycler: ViewGroup? = null
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            val name = AdapterNavigationSearch.resourceEntryName(view)
            if ((name == (if (pageIndex == 1) "fck" else "b_u") && view.javaClass.name.endsWith(".MainDesckChildViewPager")) ||
                (pageIndex == 3 && name == "h6x" && (view.parent as? View)?.let {
                    AdapterNavigationSearch.resourceEntryName(it) == "h7h"
                } == true)) targets.add(view)
            // VideoLiveFragment's footer is a separate native View, not the glass host.
            if (pageIndex == 1 && name == "bts" && view.javaClass == View::class.java) {
                plates.getOrPut(view) { view.visibility }
                view.visibility = View.INVISIBLE
            }
            if (name == "gl4" && view is ViewGroup && view.getGlobalVisibleRect(rect) && !rect.isEmpty) recycler = view
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
