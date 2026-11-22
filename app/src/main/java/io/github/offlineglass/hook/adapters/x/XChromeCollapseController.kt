package io.github.offlineglass.hook.adapters.x

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.glassHostHeightPx
import io.github.offlineglass.hook.glassHostWidthPx
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Per-host, event-driven morph. No tree/DEX scans and no transform of finished glass pixels. */
internal class XChromeCollapseController(host: GlassHostLayout) {
    private val hostRef = WeakReference(host)
    private val policy = XChromeCollapsePolicy()
    private val density = host.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var animation: ValueAnimator? = null
    private var target = false
    private var motionProgress = 0f
        private set
    val progress: Float get() = motionProgress.coerceIn(0f, 1f)
    val expanding: Boolean get() = !target
    val morphing: Boolean get() = animation?.isRunning == true || motionProgress != 0f
    val collapsedPanelSize: Int get() = collapsedSize()
    var fullWidth = 0
        private set
    var fullHeight = 0
        private set
    private var originalGravity: Int? = null
    private var originalLeft = 0
    private var originalRight = 0
    private var gesture = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private var disposed = false
    private val refreshTask = Runnable { refresh() }

    init {
        host.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(view: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(view, info)
                if (progress > 0.001f) {
                    info.className = "android.widget.Button"
                    info.contentDescription = "展开导航栏"
                    info.isClickable = true
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                }
            }
            override fun performAccessibilityAction(view: View, action: Int, arguments: Bundle?): Boolean {
                if (action == AccessibilityNodeInfo.ACTION_CLICK && progress > 0.001f) {
                    expand(); return true
                }
                return super.performAccessibilityAction(view, action, arguments)
            }
        }
    }

    fun requestRefresh() {
        hostRef.get()?.let { host ->
            host.removeCallbacks(refreshTask)
            host.post(refreshTask)
        }
    }

    fun width(base: Int): Int {
        fullWidth = base
        return XChromeCollapsePolicy.extent(base.toFloat(), collapsedSize().toFloat(), motionProgress).roundToInt()
    }
    fun height(base: Int): Int {
        fullHeight = base
        return XChromeCollapsePolicy.extent(base.toFloat(), collapsedSize().toFloat(), motionProgress).roundToInt()
    }
    private fun collapsedSize(): Int = minOf(fullHeight.coerceAtLeast(1),
        maxOf((48f * density).roundToInt(), (fullHeight * 0.78f).roundToInt()))

    fun refresh() {
        if (disposed) return
        val host = hostRef.get() ?: return
        val eligible = XAdapter.collapseEligible && !host.config.classicNavigation && host.config.enabled &&
            host.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
        val collapse = policy.update(host.config.xAutoHideBar, eligible,
            XAdapter.nativeChromeVisible, XBottomBarHooks.selectedIndex)
        if (!eligible || !host.hasWindowFocus() || !host.isShown) {
            animation?.cancel(); animation = null
            target = collapse
            motionProgress = if (collapse) 1f else 0f
        } else if (collapse != target) {
            target = collapse
            animation?.cancel()
            animation = ValueAnimator.ofFloat(motionProgress, if (collapse) 1f else 0f).apply {
                duration = (720f * abs((if (collapse) 1f else 0f) - motionProgress)).toLong().coerceIn(300L, 760L)
                interpolator = android.animation.TimeInterpolator { XChromeCollapsePolicy.springFraction(it) }
                addUpdateListener {
                    motionProgress = it.animatedValue as Float
                    applyGeometry()
                }
                start()
            }
        }
        applyGeometry()
    }

    private fun applyGeometry() {
        val host = hostRef.get() ?: return
        val parent = host.parent as? View ?: return
        val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return
        fullHeight = glassHostHeightPx(density, host.config, host.context.packageName, 5)
        fullWidth = glassHostWidthPx(parent, 5, host.config, density)
        val w = width(fullWidth)
        val h = height(fullHeight)
        val previousGravity = lp.gravity
        val previousLeft = lp.leftMargin
        val previousRight = lp.rightMargin
        if (motionProgress != 0f || target) {
            if (originalGravity == null) {
                originalGravity = lp.gravity; originalLeft = lp.leftMargin; originalRight = lp.rightMargin
            }
            // Preserve the expanded panel's left edge, also in parents with an offset/padding.
            lp.gravity = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv()) or Gravity.LEFT
            lp.leftMargin = (parent.width - parent.paddingLeft - parent.paddingRight - fullWidth) / 2
            lp.rightMargin = 0
        } else originalGravity?.let {
            lp.gravity = it; lp.leftMargin = originalLeft; lp.rightMargin = originalRight
            originalGravity = null
        }
        // Compare live params: shared config relayouts may also write these.
        val changed = lp.width != w || lp.height != h || lp.gravity != previousGravity ||
            lp.leftMargin != previousLeft || lp.rightMargin != previousRight
        lp.width = w; lp.height = h
        if (changed) {
            host.layoutParams = lp
        }
        XAdapter.updateFloatingCollapse(host, motionProgress, fullWidth, h)
        if (changed || appliedProgress != motionProgress) host.invalidate()
        appliedProgress = motionProgress
    }
    private var appliedProgress = -1f

    private fun expand() {
        policy.expand()
        refresh()
        hostRef.get()?.announceForAccessibility("导航栏已展开")
    }

    fun intercept(event: MotionEvent): Boolean? {
        if (!gesture && progress <= 0.001f && !target) return null
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesture = true; moved = false; downX = event.rawX; downY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX-downX) > slop || abs(event.rawY-downY) > slop) moved = true
            MotionEvent.ACTION_UP -> {
                val host = hostRef.get()
                val click = gesture && !moved && host != null && event.x in 0f..host.width.toFloat() &&
                    event.y in 0f..host.height.toFloat()
                gesture = false
                if (click) expand()
            }
            MotionEvent.ACTION_CANCEL -> gesture = false
        }
        return true
    }

    fun dispose() {
        disposed = true
        animation?.cancel(); animation = null
        hostRef.get()?.let { it.removeCallbacks(refreshTask); it.accessibilityDelegate = null }
        hostRef.clear()
    }
}
