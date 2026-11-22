package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowInsets
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.rendering.PostureGlass
import kotlin.math.roundToInt

/** The feed is a disjoint native sibling. Replaying its live RenderNode also includes text. */
internal class XhsNoteTopPlates(private val binding: XhsNoteTopBinding) :
    View(binding.title.context), AdapterOwnedOverlay {
    var feedback: XhsPressFeedback? = null
    private val capsules = List(4) { XhsNoteCapsule(this, "top-$it") }
    private val buttons = List(4) { RectF() }
    private val feedLocation = IntArray(2)
    private val ownLocation = IntArray(2)
    private var config = GlassConfig()
    private var sceneColor: Int? = null
    private var source: RenderNode? = null
    private var offsetX = 0f
    private var offsetY = 0f
    private var baseColor = Color.TRANSPARENT
    private var retried = false
    private var postureWanted = false
    private var gradient: GradientDrawable? = null
    private var gradientDark: Boolean? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        XhsTopTheme.watch(this)
    }

    override fun onDetachedFromWindow() {
        XhsTopTheme.unwatch(this)
        super.onDetachedFromWindow()
    }

    fun update(value: GlassConfig) {
        val nativeColor = (binding.page.background as? ColorDrawable)?.color
        if (config != value || sceneColor != nativeColor) {
            config = value
            sceneColor = nativeColor
            invalidate()
        }
        val wantsPosture = value.liquidGlassEnabled && !value.solidBarEnabled && value.postureEffectsEnabled && isShown
        if (postureWanted != wantsPosture) {
            postureWanted = wantsPosture
            PostureGlass.track(this, wantsPosture)
        }
    }

    fun place(index: Int, left: Int, top: Int, width: Int, height: Int) {
        val rect = buttons[index]
        val right = (left + width).toFloat()
        val bottom = (top + height).toFloat()
        if (rect.left == left.toFloat() && rect.top == top.toFloat() && rect.right == right && rect.bottom == bottom) return
        rect.set(left.toFloat(), top.toFloat(), right, bottom)
        capsules[index].resetBackdrop()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val feed = binding.feed
        val dark = XhsTopTheme.dark(context, config.themeMode)
        drawStatusGradient(canvas, dark)
        val background = sceneColor?.takeIf { Color.alpha(it) == 255 }
            ?: if (dark) Color.rgb(25, 25, 31) else Color.WHITE
        val nativeNode = if (!config.solidBarEnabled && canvas.isHardwareAccelerated &&
            feed.parent === binding.page && feed.isShown) XhsNoteCapsule.renderNode(feed)?.takeIf { it.hasDisplayList() }
            else null
        if (nativeNode == null && !retried && !config.solidBarEnabled && canvas.isHardwareAccelerated) {
            retried = true
            postInvalidateOnAnimation()
        } else if (nativeNode != null) retried = false
        feed.getLocationInWindow(feedLocation)
        getLocationInWindow(ownLocation)
        val x = feedLocation[0] - ownLocation[0] - feed.x
        val y = feedLocation[1] - ownLocation[1] - feed.y
        if (source !== nativeNode || offsetX != x || offsetY != y || baseColor != background) {
            capsules.forEach { it.resetBackdrop() }
            source = nativeNode
            offsetX = x
            offsetY = y
            baseColor = background
        }
        val alpha = if (config.solidBarEnabled) 255 else (config.lightAlpha * 255).toInt()
        val tint = (background and 0x00ffffff) or (alpha shl 24)
        buttons.forEachIndexed { index, rect ->
            if (rect.isEmpty) return@forEachIndexed
            val follow = index == 2
            val save = canvas.save()
            val scale = feedback?.scale(index) ?: 1f
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
            capsules[index].draw(canvas, rect, config, dark,
                if (follow) 0x33FF2442 else tint, background,
                shadowColor = if (follow) 0x4DFF2442 else XhsNoteCapsule.DEFAULT_SHADOW,
                highlightStrength = if (follow) 0.6f else 1f, reuseBackdrop = true,
                recordBackdrop = { recording ->
                    if (nativeNode != null) {
                        recording.translate(x, y)
                        recording.drawRenderNode(nativeNode)
                    }
                }, drawPress = { feedback?.drawLight(it, rect, index) })
            canvas.restoreToCount(save)
        }
    }

    private fun drawStatusGradient(canvas: Canvas, dark: Boolean) {
        val statusHeight = rootWindowInsets?.getInsets(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: binding.status.height
        if (statusHeight <= 0) return
        if (gradientDark != dark) {
            gradient = XhsTopTheme.statusGradient(dark)
            gradientDark = dark
        }
        val fade = (6 * resources.displayMetrics.density).roundToInt()
        gradient?.let {
            it.setBounds(0, 0, width, minOf(height, statusHeight + fade))
            it.draw(canvas)
        }
    }

    fun pause() {
        if (!postureWanted) return
        postureWanted = false
        PostureGlass.track(this, false)
    }

    fun release() {
        pause()
        capsules.forEach { it.release() }
        source = null
    }
}
