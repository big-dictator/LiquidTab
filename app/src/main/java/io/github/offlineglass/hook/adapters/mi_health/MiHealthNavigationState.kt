package io.github.offlineglass.hook.adapters.mi_health

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.globalGlassBottomGapPx
import android.graphics.Rect
import android.graphics.drawable.Drawable
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.compat.XposedHelpers

/** Rebinds the Material TabLayout that Mi Health recreates after device pages. */
internal class MiHealthNavigationState : AppNavigationState {
    private var lastPromoScanAt = 0L
    private var headerAddControlId = 0
    private var layoutDirty = true
    private var observedRoot: View? = null
    private var layoutRevision = 0L
    private var mineProbeRevision = -1L
    private val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener { layoutDirty = true; layoutRevision++ }

    private val hiddenPromoCards = java.util.WeakHashMap<View, Boolean>()
    private var lastScrollProbeAt = 0L
    private val topVeil = MiHealthTopVeil()
    private val deviceTopVeil = MiHealthDeviceTopVeil()
    private val headerGlass = MiHealthHeaderGlass()
    private val titleStyle = MiHealthTitleStyle()
    private val iosNavigation = MiHealthIosNavigation()
    private var navigationHost: java.lang.ref.WeakReference<GlassHostLayout>? = null
    override fun navigationOpticalMixEnabled() = navigationHost?.get()?.config?.miHealthIosIcons == true
    override fun draw(canvas: android.graphics.Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) {
        navigationHost?.get()?.let { iosNavigation.draw(canvas, it, frame) }
    }
    override fun drawForeground(canvas: android.graphics.Canvas,
        frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) { drawSource(); return true }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath); drawSource(); canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), ((1f - mix) * 255f).toInt())
            drawSource(); canvas.restoreToCount(inside)
        }
        return true
    }
    private val mineSimplifier = MiHealthMineSimplifier()
    private val mineRnTopVeil = MiHealthRnTopVeil()
    private val sportRnTopVeil = MiHealthRnTopVeil()
    private val mineTopVeil = MiHealthScrollTopVeil("root_view", "mine_header", true)
    private val sportTopVeil = MiHealthScrollTopVeil("sportRecyclerView", "title_bar", false)
    private val basePaddings = java.util.WeakHashMap<ViewGroup, IntArray>()
    private val baseBackgrounds = java.util.WeakHashMap<ViewGroup, Drawable?>()

    override fun onHostFrame(host: View) {
        if (host is GlassHostLayout) {
            if (navigationHost?.get() !== host) navigationHost = java.lang.ref.WeakReference(host)
            iosNavigation.update(host)
        }
        val now = android.os.SystemClock.uptimeMillis()
        val selected = (host as? GlassHostLayout)?.adapterSelectedIndex
        if (selected == 0) topVeil.update(host.rootView, now)
        if (selected == 2) deviceTopVeil.update(host.rootView, now)
        if (selected == 1) { sportTopVeil.update(host.rootView, now); sportRnTopVeil.update(host.rootView, now) }
        if (selected == 3) {
            mineTopVeil.update(host.rootView, now)
            val forceProbe = mineProbeRevision != layoutRevision
            mineProbeRevision = layoutRevision
            mineRnTopVeil.update(host.rootView, now, forceProbe)
        }
        mineSimplifier.update(host, if (selected == 3) mineRnTopVeil.currentScrollSource() else null)
        if (host is GlassHostLayout && selected != null) {
            val rn = when (selected) { 1 -> sportRnTopVeil; 3 -> mineRnTopVeil; else -> null }
            val nativeHeader = when (selected) {
                0 -> topVeil.currentHeader()
                2 -> deviceTopVeil.currentHeader()
                1 -> sportTopVeil.currentHeader()
                3 -> mineTopVeil.currentHeader()
                else -> null
            }
            val scene = rn?.currentRawScene() ?: when (selected) {
                0 -> topVeil.currentRawScene()
                2 -> deviceTopVeil.currentRawScene()
                1 -> sportTopVeil.currentRawScene()
                3 -> mineTopVeil.currentRawScene()
                else -> null
            }
            titleStyle.update(selected, nativeHeader, rn?.currentHeaderTitle(), host.adapterContentOrSystemDark())
            val controls = rn?.currentHeaderControls()?.takeIf { it.isNotEmpty() } ?: nativeHeader?.let { header ->
                val id = headerAddControlId.takeIf { it != 0 } ?: header.resources.getIdentifier("header_view_iv_add", "id", header.context.packageName).also { headerAddControlId = it }
                if (id != 0) header.findViewById<View>(id)?.let { listOf(MiHealthHeaderAction(it, true)) } else null
            }.orEmpty()
            headerGlass.update(host, selected, controls, scene,
                if (selected == 3) mineRnTopVeil::recordMineButtonBackdrop else null)
        }

        val currentRoot = host.rootView
        if (observedRoot !== currentRoot) {
            observedRoot?.viewTreeObserver?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener) }
            observedRoot = currentRoot
            currentRoot.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            layoutDirty = true
        }
        if (!layoutDirty || now - lastPromoScanAt < 600L) return
        layoutDirty = false
        lastPromoScanAt = now
        val root = findByIdName(host.rootView, "main_fl_content") ?: return
        if (root.width <= 0 || root.height <= 0) return
        val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (isFitnessBanner(view) && view.visibility != View.GONE && hiddenPromoCards[view] != true) {
                view.visibility = View.GONE; hiddenPromoCards[view] = true
                host.postInvalidateOnAnimation()
            }
            if (view is ViewGroup && depth < 40) for (index in 0 until view.childCount) {
                stack += view.getChildAt(index) to (depth + 1)
            }
        }
        adjustScrollSafety(host as? GlassHostLayout ?: return, now)
    }

    private fun adjustScrollSafety(host: GlassHostLayout, now: Long) {
        if (now - lastScrollProbeAt < 160L || host.width <= 0 || host.height <= 0) return
        lastScrollProbeAt = now
        val root = host.rootView as? ViewGroup ?: return
        val content = root.findViewById<ViewGroup>(android.R.id.content)
        findByIdName(content, "main_fl_content")?.let { main ->
            val stack = ArrayDeque<Pair<View, Int>>()
            for (index in 0 until main.childCount) stack += main.getChildAt(index) to 0
            var visited = 0; var changed = false
            while (stack.isNotEmpty() && visited++ < 600) {
                val (view, depth) = stack.removeLast(); if (view !is ViewGroup) continue
                if (view.visibility == View.VISIBLE && view.width >= root.width * .9f &&
                    view.height >= root.height * .3f && view.paddingBottom in 32..200) {
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0); changed = true
                }
                if (depth < 14) for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
            if (main.paddingBottom != 0) {
                main.setPadding(main.paddingLeft, main.paddingTop, main.paddingRight, 0); changed = true
            }
            if (changed) main.requestLayout()
        }
        val hostRect = Rect(); if (!host.getGlobalVisibleRect(hostRect)) return
        val wantedBottom = hostRect.top - globalGlassBottomGapPx(host)
        val candidates = ArrayList<ViewGroup>()
        val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view === host || view === host.navigationSource) continue
            if (view is ViewGroup) {
                val scrollLike = view.javaClass.name.let { it.contains("ScrollView") || it.contains("RecyclerView") || it.contains("NestedScroll") }
                val rect = Rect()
                if (scrollLike && view.visibility == View.VISIBLE && view.getGlobalVisibleRect(rect) &&
                    rect.top < hostRect.top && rect.bottom > hostRect.top && candidates.none { isDescendant(view, it) }) {
                    candidates += view
                }
                if (depth < 28) for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to depth + 1
            }
        }
        val active = candidates.toHashSet()
        basePaddings.forEach { (view, base) -> if (view.isAttachedToWindow && view !in active) {
            view.setPadding(base[0], base[1], base[2], base[3]); view.requestLayout()
            baseBackgrounds.remove(view)?.let { view.background = it }
        } }
        candidates.forEach { view ->
            val rect = Rect(); if (!view.getGlobalVisibleRect(rect)) return@forEach
            val base = basePaddings.getOrPut(view) { intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom) }
            val required = (rect.bottom - wantedBottom).coerceAtLeast(base[3])
            if (view.paddingBottom != required) {
                view.setPadding(base[0], base[1], base[2], required); view.clipToPadding = false; view.requestLayout()
            }
            baseBackgrounds.remove(view)?.let { view.background = it }
        }
    }

    private fun findByIdName(root: View?, name: String): ViewGroup? {
        if (root !is ViewGroup) return null
        if (runCatching { root.resources.getResourceEntryName(root.id) }.getOrNull() == name) return root
        for (index in 0 until root.childCount) findByIdName(root.getChildAt(index), name)?.let { return it }
        return null
    }

    private fun hostResourceName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var parent = view.parent
        while (parent is View) { if (parent === ancestor) return true; parent = parent.parent }
        return false
    }
    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        val tabLayout = findLiveTabLayout(glassHost, source) ?: return false
        if (hostResourceName(tabLayout) != "main_tl_bottom") return false
        if (index !in 0 until slotCount) return false
        val switched = iosNavigation.performTap(glassHost, index)
        if (switched) {
            // dispatchTouchEvent has already committed the tapped or released
            // slider position and started its spring animation. Snapping here
            // would cancel that animation as soon as the native tab accepts it.
            glassHost.postInvalidateOnAnimation()
            glassHost.postDelayed({ glassHost.postInvalidateOnAnimation() }, 80L)
        }
        return switched
    }

    override fun refreshNavigationSource(host: View): Boolean {
        val glassHost = host as? GlassHostLayout ?: return false
        val current = glassHost.navigationSource
        val authoritative = current != null && current.isAttachedToWindow &&
            current.width > 0 && current.height > 0 &&
            current.javaClass.name == TAB_LAYOUT_CLASS &&
            glassHost.viewResourceEntryName(current) == "main_tl_bottom"
        if (authoritative) return false
        val root = glassHost.rootView as? ViewGroup ?: return false
        val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && view !== current && view.isAttachedToWindow &&
                view.width > 0 && view.height > 0 && view.javaClass.name == TAB_LAYOUT_CLASS &&
                glassHost.viewResourceEntryName(view) == "main_tl_bottom") {
                glassHost.navigationSource = view
                view.alpha = 0f; view.background = null; view.backgroundTintList = null
                MiHealthAdapter.prepareNavigationSource(view, glassHost.resources.displayMetrics.density)
                view.post { MiHealthAdapter.prepareNavigationSource(view, glassHost.resources.displayMetrics.density) }
                glassHost.invalidateNavigationSnapshot()
                return true
            }
            if (view is ViewGroup && depth < 12) for (index in 0 until view.childCount) {
                stack += view.getChildAt(index) to (depth + 1)
            }
        }
        return false
    }

    private fun findLiveTabLayout(host: GlassHostLayout, source: ViewGroup?): ViewGroup? {
        if (source != null && source.isAttachedToWindow && source.width > 0 &&
            source.javaClass.name == TAB_LAYOUT_CLASS) return source
        val root = host.rootView as? ViewGroup ?: return null
        val stack = ArrayDeque<Pair<ViewGroup, Int>>(); stack += root to 0
        while (stack.isNotEmpty()) {
            val (group, depth) = stack.removeLast()
            if (group !== source && group.javaClass.name == TAB_LAYOUT_CLASS &&
                group.isAttachedToWindow && group.width > 0) return group
            if (depth < 8) for (index in 0 until group.childCount) {
                (group.getChildAt(index) as? ViewGroup)?.let { stack += it to (depth + 1) }
            }
        }
        return null
    }

    private fun isFitnessBanner(view: View): Boolean {
        var type: Class<*> = view.javaClass
        repeat(6) {
            if (type.name.startsWith("com.fitness.banner.export.view.") &&
                (type.simpleName == "Banner" || type.simpleName.contains("Banner"))) return true
            type = type.superclass ?: return false
        }
        return false
    }

    override fun dispose() {
        iosNavigation.dispose(); navigationHost = null
        titleStyle.dispose()
        headerGlass.dispose()
        mineSimplifier.dispose()
        observedRoot?.viewTreeObserver?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(layoutListener) }
        observedRoot = null
        topVeil.dispose()
        deviceTopVeil.dispose()
        mineRnTopVeil.dispose(); sportRnTopVeil.dispose()
        mineTopVeil.dispose()
        sportTopVeil.dispose()
        basePaddings.forEach { (view, base) -> if (view.isAttachedToWindow) view.setPadding(base[0], base[1], base[2], base[3]) }
        baseBackgrounds.forEach { (view, background) -> if (view.isAttachedToWindow) view.background = background }
        basePaddings.clear(); baseBackgrounds.clear(); hiddenPromoCards.clear()
    }

    private companion object { const val TAB_LAYOUT_CLASS = "com.google.android.material.tabs.TabLayout" }
}
