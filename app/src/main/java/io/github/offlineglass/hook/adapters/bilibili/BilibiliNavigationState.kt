package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.lang.ref.WeakReference

/** Native navigation owns page changes; glass only owns the gesture. */
internal class BilibiliNavigationState : AppNavigationState {
    private val searchFeedback = BilibiliSearchFeedback()
    override fun actionButtonPressProgress() = searchFeedback.progress
    override fun interceptNavigationTouch(host: View, event: MotionEvent): Boolean? {
        val glass = host as? io.github.offlineglass.hook.GlassHostLayout ?: return null
        searchFeedback.touch(host, event, BilibiliAdapter.barLayout(glass.config, host.width,
            host.height, host.resources.displayMetrics.density, glass.adapterSlotCount)?.actionIndex == BilibiliAdapter.SEARCH_INDEX)
        return null
    }
    override fun drawPanelEdgeGlow(canvas: android.graphics.Canvas, path: android.graphics.Path, density: Float) =
        searchFeedback.drawLight(canvas, path)
    override val persistentRestingIndicatorFill = true
    override val retainHiddenSourceFillWhileExpanded = true
    private var getterClass: Class<*>? = null
    private var selectionGetter: java.lang.reflect.Method? = null
    private var cellApiClass: Class<*>? = null
    private var cellCountGetter: java.lang.reflect.Method? = null
    private var cellGetter: java.lang.reflect.Method? = null
    private var transitionUntil = 0L
    private var selectionReadAt = 0L
    private var cachedSelection: Int? = null
    private var lastDispatchedSelection = -1
    private var gestureActive = false
    private var pendingSelection: Int? = null
    private var selectionGuardUntil = 0L
    private var activeSource: WeakReference<ViewGroup>? = null
    override fun onHostFrame(host: View) {
        val glass = host as? io.github.offlineglass.hook.GlassHostLayout ?: return
        BilibiliShopDormancy.bind(glass)
    }

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
    override fun drawForeground(canvas: android.graphics.Canvas,
        frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean =
        BilibiliOpticalForeground.draw(canvas, frame, selectionPath, drawSource)

    override fun draw(canvas: android.graphics.Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) =
        BilibiliRenderer.draw(canvas, frame, searchFeedback.progress)

    // Theme discovery may wait for native selection, but optical references
    // must follow the newly visible page even while its slider is animating.
    override fun deferThemeDetection(now: Long) = now < transitionUntil

    // The native row keeps its original listener/hit hierarchy. Recursively
    // marking decorative children clickable makes them swallow their tab's tap
    // after the overlay returns from a hidden page.
    override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true

    override fun onBarGesture(phase: Int, host: View) {
        gestureActive = phase != 2
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) = resetTransientState()

    private fun resetTransientState() {
        searchFeedback.reset()
        BilibiliShopDormancy.release()
        transitionUntil = 0L
        selectionReadAt = 0L
        cachedSelection = null
        gestureActive = false
        pendingSelection = null
        selectionGuardUntil = 0L
        activeSource = null
    }

    override fun onHostDetached() = resetTransientState()

    override fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean? {
        if (gestureActive) return false
        val pending = pendingSelection ?: return null
        if (index == pending || now >= selectionGuardUntil) {
            pendingSelection = null
            return null
        }
        return false
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val current = source?.takeIf { it.isAttachedToWindow }
            ?: activeSource?.get()?.takeIf { it.isAttachedToWindow } ?: return null
        // Verified on current TabHost: this getter uses the same index as its
        // click listener. Never invoke setCurrentItem: it can skip page callbacks.
        val now = SystemClock.uptimeMillis()
        if (getterClass === current.javaClass && now - selectionReadAt < 80L) return cachedSelection
        selectionReadAt = now
        if (getterClass !== current.javaClass) {
            cachedSelection = null
            getterClass = current.javaClass
            selectionGetter = runCatching { current.javaClass.getMethod("getCurrentItem") }
                .getOrNull()?.takeIf { it.returnType == Int::class.javaPrimitiveType }
        }
        val method = selectionGetter ?: return null
        cachedSelection = runCatching {
            (method.invoke(current) as? Int)?.takeIf { it in 0 until slotCount && it != 2 }
        }.getOrNull()
        return cachedSelection
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (index == BilibiliAdapter.SEARCH_INDEX) {
            val opened = BilibiliHomeLayoutHooks.openSearch(host.rootView)
            if (io.github.offlineglass.BuildConfig.DEBUG)
                android.util.Log.i("LiquidTabBiliSearch", "action dispatch opened=$opened")
            return false
        }
        if (index == BilibiliAdapter.SHOP_INDEX || cachedSelection == BilibiliAdapter.SHOP_INDEX ||
            lastDispatchedSelection == BilibiliAdapter.SHOP_INDEX) {
            (host as? io.github.offlineglass.hook.GlassHostLayout)?.let {
                io.github.offlineglass.hook.AnimationResourceGuard.begin(it)
            }
        }
        if (slotCount != 5 || index !in 0 until slotCount) return false
        val current = source?.takeIf { ready(it) && it.rootView === host.rootView }
            ?: BilibiliAdapter.findNavigation(host.rootView, targetSpec)?.view
            ?: return false
        activeSource = WeakReference(current)
        pendingSelection = index.takeUnless { it == BilibiliAdapter.PUBLISH_INDEX }
        selectionGuardUntil = SystemClock.uptimeMillis() + 300L
        // Defer theme probing only. Retaining the old backdrop here bypasses
        // the shared tap handoff and exposes sharp destination-page pixels.
        transitionUntil = SystemClock.uptimeMillis() + 180L
        android.os.Trace.beginSection("LiquidTab.TabBinding")
        val cell = try { originalTabCell(current, index, slotCount)
            ?: nativeCells(current, slotCount)?.getOrNull(index)
        } finally { android.os.Trace.endSection() }
        android.os.Trace.beginSection("LiquidTab.TabDispatch")
        val handled = if (cell != null) {
            // Exactly one action; do not retry after a listener was invoked.
            runCatching { cell.performClick() }.getOrDefault(false)
        } else {
            dispatchNativeTouch(current, index, slotCount)
        }
        android.os.Trace.endSection()
        // Invalidate AFTER native callbacks, which may synchronously redraw
        // while the controller still reports the old selection.
        selectionReadAt = 0L
        if (handled && index != BilibiliAdapter.PUBLISH_INDEX) lastDispatchedSelection = index
        if (!handled) {
            pendingSelection = null
            XposedBridge.log("LiquidTab Bilibili: native input rejected (${current.javaClass.name}); subsequent gestures remain enabled")
        }
        return handled
    }

    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        // Observe native selection after synchronous listeners and posted work.
        requestRefresh(0L)
        requestRefresh(190L)
    }

    private fun ready(view: View) = view.isAttachedToWindow && view.isShown &&
        view.isEnabled && view.width > 0 && view.height > 0

    private fun originalTabCell(source: ViewGroup, index: Int, count: Int): View? = runCatching {
        if (cellApiClass !== source.javaClass) {
            cellApiClass = source.javaClass
            cellCountGetter = runCatching { source.javaClass.getMethod("getItemCount") }.getOrNull()
                ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
            cellGetter = if (cellCountGetter == null) null else source.javaClass.methods.filter {
                !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                    it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                    it.returnType == ViewGroup::class.java
            }.singleOrNull()
        }
        val countGetter = cellCountGetter ?: return@runCatching null
        if (countGetter.invoke(source) != count) return@runCatching null
        // Current 9.14.0 exposes k(int):ViewGroup. Match its public ABI instead
        // of the obfuscated name, and validate the returned cell's ownership.
        val getter = cellGetter ?: return@runCatching null
        val cell = getter.invoke(source, index) as? View ?: return@runCatching null
        var ancestor = cell.parent
        repeat(10) {
            if (ancestor === source) return@runCatching cell.takeIf { it.hasOnClickListeners() }
            ancestor = ancestor?.parent
        }
        null
    }.getOrNull()


    private fun nativeCells(source: ViewGroup, count: Int): List<View>? {
        val stack = ArrayDeque<Pair<ViewGroup, Int>>()
        stack.add(source to 0)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 128) {
            val (group, depth) = stack.removeFirst()
            if (group.childCount == count) {
                val cells = (0 until count).map { group.getChildAt(it) }
                if (cells.all { ready(it) && it.hasOnClickListeners() }) {
                    val bounds = cells.map { child ->
                        Rect(0, 0, child.width, child.height).also {
                            source.offsetDescendantRectToMyCoords(child, it)
                        }
                    }
                    // A child count alone can select a decorative wrapper. Require
                    // five distinct cells ordered across the native navigation row.
                    val unit = source.width.toFloat() / count
                    if (bounds.withIndex().all { (i, rect) ->
                            rect.width() in (unit * 0.5f).toInt()..(unit * 1.5f).toInt() &&
                                kotlin.math.abs(rect.exactCenterX() - unit * (i + 0.5f)) < unit * 0.4f
                        }) return cells
                }
            }
            if (depth < 8) for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? ViewGroup)?.let { stack.add(it to depth + 1) }
            }
        }
        return null
    }

    private fun dispatchNativeTouch(source: ViewGroup, index: Int, count: Int): Boolean {
        val x = source.width * (index + 0.5f) / count
        val y = source.height / 2f
        val now = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long): Boolean {
            val event = MotionEvent.obtain(now, time, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { source.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        return runCatching {
            if (!send(MotionEvent.ACTION_DOWN, now)) {
                send(MotionEvent.ACTION_CANCEL, now)
                false
            } else {
                // Dispatch directly to the source, never Activity/root: that
                // would recursively hit the glass overlay or unrelated content.
                send(MotionEvent.ACTION_UP, now + 32L)
                // DOWN claimed the gesture. UP may return false after launching
                // a page/dialog; that does not justify disabling future touches.
                true
            }
        }.getOrElse {
            runCatching { send(MotionEvent.ACTION_CANCEL, now + 32L) }
            false
        }
    }
}
