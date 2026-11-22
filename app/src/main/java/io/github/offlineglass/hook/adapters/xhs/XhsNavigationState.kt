package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.ViewGroup
import android.view.View
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Xiaohongshu's five fixed slots; middle publish action is drawn, not a page label. */
internal class XhsNavigationState : AppNavigationState {
    private var lastActionScan = Long.MIN_VALUE
    private var lastListScan = Long.MIN_VALUE
    private val messageLists = java.util.WeakHashMap<ViewGroup, ListPadding>()
    private data class ListPadding(val bottom: Int, val clip: Boolean, var applied: Int)
    private val hostPosition = IntArray(2)
    private val listPosition = IntArray(2)
    private val messageViewport = XhsMessageViewport()
    private val homeViewport = XhsHomeViewport()
    private val bottomGradient = XhsBottomGradient()

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
        density: Float, barHeightPx: Int, surfaceColor: () -> Int,
    ) {
        bottomGradient.update(host, enabled)
        if (enabled) homeViewport.update(host.rootView) else homeViewport.restore()
        if (!enabled || selected() != 3) {
            messageViewport.restore()
            restoreListPadding()
            lastListScan = Long.MIN_VALUE
            return
        }
        val now = SystemClock.uptimeMillis()
        if (lastListScan == Long.MIN_VALUE || now - lastListScan >= 250L) {
            lastListScan = now
            val stack = ArrayDeque<View>()
            (host.rootView as? ViewGroup)?.let { stack.add(it) }
            while (stack.isNotEmpty()) {
                val view = stack.removeLast()
                if (view === host || view === source || view is GlassHostLayout || view is AdapterOwnedOverlay || !view.isShown) continue
                messageViewport.discover(view)
                if (view is ViewGroup && isRecyclerList(view) && view.height > barHeightPx * 2 && view.width > host.width / 2) {
                    messageLists.getOrPut(view) { ListPadding(view.paddingBottom, view.clipToPadding, view.paddingBottom) }
                    // Do not discover rows or nested carousels inside the main list.
                    continue
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
        }
        messageViewport.update()
        host.getLocationInWindow(hostPosition)
        val gap = (12f * density).roundToInt()
        for ((list, padding) in messageLists) {
            if (!list.isAttachedToWindow || !list.isShown) continue
            list.getLocationInWindow(listPosition)
            // Padding adds scroll range; disabling padding clipping preserves full-bleed drawing.
            val clearance = (listPosition[1] + list.height - hostPosition[1] + gap).coerceAtLeast(0)
            val wanted = maxOf(padding.bottom, clearance)
            if (list.paddingBottom != wanted) {
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, wanted)
            }
            padding.applied = wanted
            if (list.clipToPadding) list.clipToPadding = false
        }
    }

    private fun restoreListPadding() {
        for ((list, padding) in messageLists) {
            if (list.paddingBottom == padding.applied && list.paddingBottom != padding.bottom) {
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, padding.bottom)
            }
            list.clipToPadding = padding.clip
        }
        messageLists.clear()
    }

    private fun isRecyclerList(view: View): Boolean {
        return XhsTopBinding.isRecycler(view)
    }

    override fun dispose() {
        bottomGradient.clear()
        homeViewport.restore()
        messageViewport.restore()
        restoreListPadding()
        for ((view, base) in xhsFloatingActionBaseTranslations) view.translationY = base
        xhsFloatingActionBaseTranslations.clear()
    }
    private val xhsFloatingActionBaseTranslations = java.util.WeakHashMap<View, Float>()
    private val actionHostPosition = IntArray(2)
    private val actionPosition = IntArray(2)
    private val renderer = XhsNavigationRenderer()
    private var diagnosticTapGeneration = 0

    private fun pagerIndex(view: View): Int? =
        runCatching { view.javaClass.getMethod("getCurrentItem").invoke(view) as? Int }.getOrNull()

    private fun pagerCount(view: View): Int? = runCatching {
        val adapter = view.javaClass.getMethod("getAdapter").invoke(view) ?: return null
        adapter.javaClass.getMethod("getCount").invoke(adapter) as? Int
    }.getOrNull()

    private fun diagnosticPager(host: View): View? {
        val queue = ArrayDeque<View>()
        queue.add(host.rootView)
        var result: View? = null
        repeat(600) {
            val view = queue.removeFirstOrNull() ?: return result
            if (view is ViewGroup && view.javaClass.name == SCROLLABLE_PAGER_CLASS &&
                view.isShown && (pagerCount(view) ?: -1) in 4..5 && pagerIndex(view) != null) {
                if (result != null) return null
                result = view
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return null
    }

    private fun observePagerCommit(pager: View, index: Int, previous: Int) {
        val target = if (pagerCount(pager) == 4 && index > 2) index - 1 else index
        if (target == previous || !pager.isHardwareAccelerated) return
        val generation = ++diagnosticTapGeneration
        val observer = pager.viewTreeObserver
        if (!observer.isAlive) return
        val listener = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (generation != diagnosticTapGeneration || pagerIndex(pager) == target) {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    if (generation == diagnosticTapGeneration && pager.isShown && observer.isAlive) {
                        observer.registerFrameCommitCallback {
                            if (generation == diagnosticTapGeneration && pagerIndex(pager) == target &&
                                pager.isShown && android.os.Trace.isEnabled()) {
                                android.os.Trace.beginSection("LiquidTab.Xhs.PagerCommitCandidate.$index")
                                android.os.Trace.endSection()
                            }
                        }
                    }
                }
                return true
            }
        }
        observer.addOnPreDrawListener(listener)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        }, 1800L)
    }
    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val tabBar = findTabBar(host, source) ?: return false
        val getter = TAB_GETTERS.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return false
        val target = runCatching { XposedHelpers.callMethod(tabBar, getter) as? View }.getOrNull()
            ?: return false
        val pager = if (index != 2 && io.github.offlineglass.BuildConfig.DEBUG && android.os.Trace.isEnabled())
            diagnosticPager(host) else null
        val before = pager?.let(::pagerIndex)
        val clicked = performClickThroughTab(target, tabBar)
        if (clicked && pager != null && before != null) observePagerCommit(pager, index, before)
        if (clicked && index == 2) {
            // Publish opens a native action, not a page. This callback's true
            // result commits the page index in the host; keep the existing page.
            host.postInvalidateOnAnimation()
            return false
        }
        return clicked
    }
    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        requestRefresh(0L)
        requestRefresh(SELECTION_SETTLE_MS)
    }
    private fun findTabBar(host: View, source: ViewGroup?): ViewGroup? {
        if (source != null && source.javaClass.name == TAB_BAR_CLASS) return source
        val root = source ?: (host as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return null
        val stack = ArrayDeque<Pair<ViewGroup, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (group, depth) = stack.removeLast()
            if (group.javaClass.name == TAB_BAR_CLASS) return group
            if (depth < 5) {
                for (childIndex in 0 until group.childCount) {
                    (group.getChildAt(childIndex) as? ViewGroup)?.let { stack += it to (depth + 1) }
                }
            }
        }
        return null
    }
    private fun performClickThroughTab(target: View, boundary: ViewGroup): Boolean {
        var current: View? = target
        repeat(6) {
            val view = current ?: return@repeat
            if (view.isShown && view.performClick()) return true
            if (view === boundary) return@repeat
            current = view.parent as? View
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += target to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view !== target && view.isShown && view.isClickable && view.performClick()) return true
            if (view is ViewGroup && depth < 4) {
                for (childIndex in 0 until view.childCount) stack += view.getChildAt(childIndex) to (depth + 1)
            }
        }
        return false
    }
    override fun suppressNativeChromeEarly(source: ViewGroup?) {
        if (source == null || !source.javaClass.name.endsWith(".TabBarView")) return
        if (source.alpha != 0f) source.alpha = 0f
        if (source.background != null) source.background = null
        val parent = source.parent as? ViewGroup ?: return
        val location = IntArray(2).also(parent::getLocationInWindow)
        val rootHeight = source.rootView.height.coerceAtLeast(1)
        val isBottomShell = parent.height in 1..(source.height * 2).coerceAtLeast(1) &&
            location[1] + parent.height >= rootHeight * 0.78f
        if (isBottomShell && parent.background != null) parent.background = null
    }
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true

    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        if (frame.detachedDirectSelection && !selectionPath.isEmpty) {
            val outside = canvas.save()
            try {
                canvas.clipOutPath(selectionPath)
                drawSource()
            } finally { canvas.restoreToCount(outside) }
            val inside = canvas.save()
            try {
                canvas.clipPath(selectionPath)
                renderer.draw(canvas, frame.copy(
                    extraScale = (frame.height / (64f * frame.density)).coerceIn(0.875f, 1f),
                    hideSelectedForOptics = true,
                ))
            } finally { canvas.restoreToCount(inside) }
            return true
        }
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) { drawSource(); return true }
        // Match Spotify's live geometry mask: outside is always normal artwork;
        // inside comes from the selected-artwork scene refracted by the lens.
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = renderer.draw(canvas, frame)
    /**

     * XHS places transient circular actions (for example, back-to-top) against

     * the physical bottom inset, independently of TabBarView.  Once the tab row

     * becomes a floating glass panel those actions can sit underneath it.  Move

     * only compact, clickable, right-edge actions that actually intersect the

     * glass safety zone.  Their bottom gap above the glass mirrors the approved

     * glass-to-screen bottom gap.

     */

    override fun adjustFloatingActions(host: android.view.View, source: ViewGroup?) {
        // Shared pre-draw can call this twice. Discovery never needs to walk
        // the entire feed at display refresh rate, especially during tab switches.
        val now = SystemClock.uptimeMillis()
        if (lastActionScan != Long.MIN_VALUE && now - lastActionScan < 200L) return
        lastActionScan = now
        val density = host.resources.displayMetrics.density

        if (!host.isAttachedToWindow || !host.isShown || host.windowVisibility != View.VISIBLE) return

        val root = host.rootView as? ViewGroup ?: return

        val hostLocation = actionHostPosition.also(host::getLocationOnScreen)

        val hostTop = hostLocation[1]

        if (hostTop <= 0 || host.width <= 0 || host.height <= 0) return

        val bottomGap = ((host.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)

            .coerceAtLeast((12f * density).roundToInt())

        val desiredBottom = hostTop - bottomGap

        val minimumSize = 36f * density

        val maximumSize = 92f * density

        val rightEdge = root.width * 0.68f

        val touched = HashSet<View>()

        

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view === host || view === source || view is GlassHostLayout || view is AdapterOwnedOverlay) continue

            if (view.visibility != View.VISIBLE || view.alpha <= 0f) continue



            val compact = view.width in minimumSize.roundToInt()..maximumSize.roundToInt() &&

                view.height in minimumSize.roundToInt()..maximumSize.roundToInt()

            if (compact && view.isClickable && view.isShown) {

                val location = actionPosition.also(view::getLocationOnScreen)

                val base = xhsFloatingActionBaseTranslations.getOrPut(view) { view.translationY }

                val appliedDelta = view.translationY - base

                val originalBottom = location[1] + view.height - appliedDelta

                val originalTop = originalBottom - view.height

                val rightAligned = location[0] + view.width / 2f >= rightEdge

                val intersectsSafetyZone = originalBottom > desiredBottom && originalTop < hostTop + host.height

                if (rightAligned && intersectsSafetyZone) {

                    val lift = originalBottom - desiredBottom

                    val wantedTranslation = base - lift

                    if (abs(view.translationY - wantedTranslation) > 0.5f) {

                        view.translationY = wantedTranslation

                    }

                    touched += view

                    var ancestor = view.parent as? ViewGroup

                    repeat(4) {

                        ancestor?.clipChildren = false

                        ancestor?.clipToPadding = false

                        ancestor = ancestor?.parent as? ViewGroup

                    }

                }

            }

            // Feed cells are business content, never floating page chrome.
            // Do not inspect hundreds of card descendants every 200ms.
            if (view is ViewGroup && depth < 14 && !isRecyclerList(view)) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }



        xhsFloatingActionBaseTranslations.entries.forEach { (view, base) ->

            if (view !in touched && view.isAttachedToWindow && view.translationY != base) {

                view.translationY = base

            }

        }

    }




    private companion object {
        const val SELECTION_SETTLE_MS = 96L
        const val TAB_BAR_CLASS = "com.xingin.xhs.homepage.tabbar.TabBarView"
        val TAB_GETTERS = arrayOf(
            "getIndexHome", "getIndexStore", "getIndexPost", "getIndexMessage", "getIndexMe",
        )
        const val SCROLLABLE_PAGER_CLASS = "com.xingin.redview.viewpage.ExploreScrollableViewPager"
    }
}
