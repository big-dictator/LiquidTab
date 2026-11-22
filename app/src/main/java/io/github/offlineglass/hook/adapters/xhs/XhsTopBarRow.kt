package io.github.offlineglass.hook.adapters.xhs

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/** Lays out the glass and touch targets together without requesting a window layout per frame. */
internal class XhsTopBarRow(
    context: Context,
    left: View,
    private val tabs: View,
    private val channel: View,
    right: View,
    private val labels: List<TextView>,
) : FrameLayout(context) {
    private val controls = arrayOf(left, tabs, channel, right)
    private val glass = arrayOfNulls<View>(controls.size)
    private val easing = PathInterpolator(.2f, 0f, 0f, 1f)
    private var animator: ValueAnimator? = null
    private var initialized = false
    private var targetWidth = 0
    private var targetSelection = -1
    private var targetInk = 0
    private var channelWidth = 0f
    private var channelAlpha = 0f

    init {
        clipChildren = false
        clipToPadding = false
        channel.visibility = INVISIBLE
        controls.forEach { addView(it, LayoutParams(0, 0)) }
    }

    fun mountGlass(index: Int, view: View) {
        glass[index] = view
        if (index == 2) view.visibility = channel.visibility
        addView(view, 0, LayoutParams(0, 0))
    }

    fun update(newWidth: Int, selection: Int, ink: Int, animate: Boolean) {
        if (initialized && newWidth == targetWidth && selection == targetSelection && ink == targetInk) return
        animator?.cancel()
        animator = null
        if (targetSelection != selection) glass[1]?.invalidate()
        targetWidth = newWidth
        targetSelection = selection
        targetInk = ink
        val show = newWidth > 0
        channel.isClickable = show
        channel.isFocusable = show
        channel.importantForAccessibility =
            if (show) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        val startWidth = channelWidth
        val startAlpha = channelAlpha
        val endAlpha = if (show) 1f else 0f
        val startColors = labels.map { it.currentTextColor }
        fun apply(fraction: Float) {
            channelWidth = startWidth + (newWidth - startWidth) * fraction
            channelAlpha = startAlpha + (endAlpha - startAlpha) * fraction
            labels.forEachIndexed { index, label ->
                val color = if (index == selection) 0xFFFF2442.toInt() else ink
                val blended = ColorUtils.blendARGB(startColors[index], color, fraction)
                if (label.currentTextColor != blended) label.setTextColor(blended)
            }
            if (startWidth != newWidth.toFloat() || startAlpha != endAlpha) layoutControls()
        }
        if (initialized && animate && width > 0) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220L
                interpolator = easing
                addUpdateListener { apply(it.animatedValue as Float) }
                start()
            }
        } else {
            initialized = true
            apply(1f)
        }
    }

    fun resetMotion() {
        animator?.cancel()
        animator = null
        initialized = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        layoutControls()
    }

    private fun layoutControls() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val gap = (7f * density).roundToInt()
        val button = minOf(height, (width - 2 * gap - 1).coerceAtLeast(0) / 2)
        val available = (width - 2 * button - 2 * gap).coerceAtLeast(1)
        val extraGap = (gap * channelAlpha).roundToInt().coerceAtMost(available - 1)
        val actionWidth = channelWidth.roundToInt().coerceIn(0, available - extraGap - 1)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        tabs.measure(MeasureSpec.makeMeasureSpec(available - actionWidth - extraGap, MeasureSpec.AT_MOST), heightSpec)
        val total = 2 * button + 2 * gap + tabs.measuredWidth + actionWidth + extraGap
        val start = (width - total) / 2
        val tabStart = start + button + gap
        val channelStart = tabStart + tabs.measuredWidth + gap
        val shown = actionWidth > 0 && channelAlpha > 0f
        channel.alpha = channelAlpha
        glass[2]?.alpha = channelAlpha
        val channelVisibility = if (shown) VISIBLE else INVISIBLE
        if (channel.visibility != channelVisibility) channel.visibility = channelVisibility
        glass[2]?.let { if (it.visibility != channelVisibility) it.visibility = channelVisibility }
        place(0, start, button, heightSpec)
        place(1, tabStart, tabs.measuredWidth, heightSpec)
        if (shown) place(2, channelStart, actionWidth, heightSpec)
        place(3, channelStart + actionWidth + extraGap, button, heightSpec)
    }

    private fun place(index: Int, start: Int, childWidth: Int, heightSpec: Int) {
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - start - childWidth else start
        val right = left + childWidth
        val widthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
        val control = controls[index]
        if (control !== tabs) control.measure(widthSpec, heightSpec)
        if (control.isLayoutRequested || control.left != left || control.top != 0 ||
            control.right != right || control.bottom != height) control.layout(left, 0, right, height)
        glass[index]?.let { host ->
            host.measure(widthSpec, heightSpec)
            if (host.isLayoutRequested || host.left != left || host.top != 0 ||
                host.right != right || host.bottom != height) {
                host.layout(left, 0, right, height)
                host.invalidate()
            }
        }
    }
}
