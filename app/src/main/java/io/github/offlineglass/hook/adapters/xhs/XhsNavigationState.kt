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
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState

/** Xiaohongshu's five fixed slots; middle publish action is drawn, not a page label. */
internal class XhsNavigationState : AppNavigationState {
    private var lastActionScan = Long.MIN_VALUE
    private var lastListScan = Long.MIN_VALUE
    private val messageLists = java.util.WeakHashMap<ViewGroup, ListPadding>()
    private data class ListPadding(val bottom: Int, val clip: Boolean, var applied: Int)
    private val hostPosition = IntArray(2)
    private val listPosition = IntArray(2)
    private val messageViewport = XhsMessageViewport()

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
        density: Float, barHeightPx: Int, surfaceColor: () -> Int,
    ) {
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
                if (view === host || view === source || !view.isShown) continue
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
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "androidx.recyclerview.widget.RecyclerView" ||
                type.name == "android.support.v7.widget.RecyclerView") return true
            type = type.superclass
        }
        return false
    }

    override fun dispose() {
        messageViewport.restore()
        restoreListPadding()
        for ((view, base) in xhsFloatingActionBaseTranslations) view.translationY = base
        xhsFloatingActionBaseTranslations.clear()
    }
    private val xhsFloatingActionBaseTranslations = java.util.WeakHashMap<View, Float>()
    private val xhsLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val bilibiliIconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val tabBar = findTabBar(host, source) ?: return false
        val getter = TAB_GETTERS.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return false
        val target = runCatching { XposedHelpers.callMethod(tabBar, getter) as? View }.getOrNull()
            ?: return false
        return performClickThroughTab(target, tabBar)
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
        removeNativeTabInset(parent)
        val location = IntArray(2).also(parent::getLocationInWindow)
        val rootHeight = source.rootView.height.coerceAtLeast(1)
        val isBottomShell = parent.height in 1..(source.height * 2).coerceAtLeast(1) &&
            location[1] + parent.height >= rootHeight * 0.78f
        if (isBottomShell && parent.background != null) parent.background = null
    }
    private fun removeNativeTabInset(tabBarParent: ViewGroup) {
        val indexRoot = tabBarParent.parent as? ViewGroup ?: return
        for (index in 0 until indexRoot.childCount) {
            val content = indexRoot.getChildAt(index) as? ViewGroup ?: continue
            if (!content.javaClass.name.endsWith(".ContentView")) continue
            for (childIndex in 0 until content.childCount) {
                val pager = content.getChildAt(childIndex)
                if (pager.javaClass.name != SCROLLABLE_PAGER_CLASS) continue
                val params = pager.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                if (params.bottomMargin != 0) {
                    params.bottomMargin = 0
                    pager.layoutParams = params
                    pager.requestLayout()
                    content.requestLayout()
                }
                return
            }
        }
    }
    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = with(frame) {

        if (slotCount != 5 || width <= 0 || height <= 0) return

        val slotWidth = slotWidth

        val horizontalPadding = 4f * density

        val dark = darkGlass

        val unselectedColor = if (dark) Color.rgb(232, 230, 235) else Color.rgb(40, 40, 40)

        xhsLabelPaint.textSize = 15f * XHS_LABEL_SCALE * scaledDensity

        val metrics = xhsLabelPaint.fontMetrics

        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f



        for ((visualIndex, index) in enabledIndices.withIndex()) {

            val centerX = horizontalPadding + (visualIndex + 0.5f) * slotWidth

            canvas.save()

            canvas.scale(extraScale, extraScale, centerX, height / 2f)

            if (index == XHS_PUBLISH_INDEX) {

                val half = 20f * density

                val publishRect = RectF(

                    centerX - half,

                    height / 2f - half,

                    centerX + half,

                    height / 2f + half,

                )

                bilibiliIconPaint.style = Paint.Style.FILL

                bilibiliIconPaint.color = accentColor

                canvas.drawRoundRect(publishRect, 12f * density, 12f * density, bilibiliIconPaint)

                bilibiliIconPaint.style = Paint.Style.STROKE

                bilibiliIconPaint.strokeWidth = 3.2f * density

                bilibiliIconPaint.strokeCap = Paint.Cap.ROUND

                bilibiliIconPaint.color = Color.WHITE

                canvas.drawLine(centerX - 7f * density, height / 2f, centerX + 7f * density, height / 2f, bilibiliIconPaint)

                canvas.drawLine(centerX, height / 2f - 7f * density, centerX, height / 2f + 7f * density, bilibiliIconPaint)

                bilibiliIconPaint.style = Paint.Style.FILL

            } else {

                xhsLabelPaint.color = if (index == selectedIndex) accentColor else unselectedColor

                canvas.drawText(labels[index], centerX, baseline, xhsLabelPaint)

            }

            canvas.restore()

        }

    }




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

        if (!host.isAttachedToWindow) return

        val root = host.rootView as? ViewGroup ?: return

        val hostLocation = IntArray(2).also(host::getLocationOnScreen)

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

            if (view === host || view === source) continue

            if (view.visibility != View.VISIBLE || view.alpha <= 0f) continue



            val compact = view.width in minimumSize.roundToInt()..maximumSize.roundToInt() &&

                view.height in minimumSize.roundToInt()..maximumSize.roundToInt()

            if (compact && view.isClickable && view.isShown) {

                val location = IntArray(2).also(view::getLocationOnScreen)

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

            if (view is ViewGroup && depth < 14) {

                for (index in view.childCount - 1 downTo 0) {

                    stack += view.getChildAt(index) to (depth + 1)

                }

            }

        }



        xhsFloatingActionBaseTranslations.entries.toList().forEach { (view, base) ->

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
        const val XHS_LABEL_SCALE = 1.2f
        const val XHS_PUBLISH_INDEX = 2
        val labels = arrayOf("首页", "市集", "", "消息", "我")
    }
}
