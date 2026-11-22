package io.github.offlineglass.hook.adapters.wechat


import android.content.Context
import android.graphics.*
import android.widget.TextView
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.PostureGlass
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import java.lang.ref.WeakReference
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

internal class WeChatFoldTouchProxy(context: Context) : View(context) {
    val shadowInset: Int get() = (16f * resources.displayMetrics.density).toInt()
    private var glass = WeakReference<GlassHostLayout>(null)
    private var scene = WeakReference<View>(null)
    private var title = "折叠置顶聊天"
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shape = Path()
    private val node = RenderNode("WeChatFoldButton")
    private val lens = PostureGlass.shader(KYANT_LENS_SHADER, flowing = false)
    private val nodeField = View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
    private val backdropPosition = IntArray(2)
    private val backdropOrigin = IntArray(2)
    private val backdropInputs = io.github.offlineglass.rendering.ExactRenderInputs(5)
    private var effectKey = ""
    private var arrowRotation = 0f
    private var arrowTarget = 0f
    private var collapsed: Boolean? = null
    private var arrowAnimation: android.animation.ValueAnimator? = null
    var togglePinnedChats: (() -> Boolean)? = null
    private var pressedPlanB = false

    fun setFoldState(next: Boolean?) {
        if (next == collapsed) return
        val hadState = collapsed != null
        collapsed = next
        contentDescription = if (next == true) "展开置顶聊天" else "收起置顶聊天"
        if (next == null) return
        arrowAnimation?.cancel()
        if (!hadState || !visibilityTarget) {
            arrowTarget = if (next) 0f else 180f
            arrowRotation = arrowTarget
            invalidate()
            return
        }
        val start = arrowRotation
        arrowTarget += 180f
        arrowAnimation = android.animation.ValueAnimator.ofFloat(start, arrowTarget).apply {
            duration = 220L
            addUpdateListener {
                arrowRotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private var visibilityTarget = false
    var planB = false
        private set

    fun setPlanB(enabled: Boolean) {
        if (planB == enabled) return
        planB = enabled
        arrowAnimation?.cancel()
        collapsed = null
        if (enabled) contentDescription = "收起置顶聊天"
        animate().cancel()
        visibilityTarget = false
        alpha = 0f
        scaleX = 1f
        scaleY = 1f
        visibility = INVISIBLE
        effectKey = ""
        invalidate()
    }

    fun showAnimated() {
        isEnabled = true
        if (visibilityTarget) return
        visibilityTarget = true
        animate().cancel()
        if (visibility != VISIBLE) {
            alpha = 0f
            if (planB) {
                scaleX = 0f
                scaleY = 0f
            }
        }
        visibility = VISIBLE
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setInterpolator(if (planB) planBInterpolator else android.view.animation.AccelerateDecelerateInterpolator()).setDuration(if (planB) 240L else 180L).withEndAction(null).start()
    }

    fun hideAnimated() {
        isEnabled = false
        if (!visibilityTarget) return
        visibilityTarget = false
        animate().cancel()
        // View alpha includes the shadow padding and highlight, not just fill.
        val exit = animate().alpha(0f).setDuration(if (planB) 240L else 160L)
        if (planB) {
            exit.scaleX(0f).scaleY(0f).setInterpolator(planBInterpolator)
        }
        exit.withEndAction {
            if (!visibilityTarget) {
                visibility = GONE
                PostureGlass.track(this, false)
            }
        }.start()
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        arrowAnimation?.cancel()
        backdropInputs.clear()
        node.discardDisplayList()
        visibilityTarget = false
        visibility = GONE
        alpha = 0f
        PostureGlass.track(this, false)
        super.onDetachedFromWindow()
    }

    fun configure(host: GlassHostLayout, bar: ViewGroup?) {
        val changedSource = glass.get() !== host || scene.get() !== host.parent
        if (changedSource) {
            glass = WeakReference(host)
            scene = WeakReference(host.parent as? View)
            invalidate()
        }
        if (bar == null) return
        val labels = ArrayList<String>()
        fun collect(view: View) {
            if (view.visibility != VISIBLE) return
            if (view is TextView && !view.text.isNullOrBlank()) labels += view.text.toString()
            if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(bar)
        val nextTitle = labels.joinToString(" ").ifBlank { "折叠置顶聊天" }
        val changedTitle = nextTitle != title
        title = nextTitle
        contentDescription = title
        ink.textSize = 14f * resources.displayMetrics.scaledDensity
        if (changedTitle || changedSource) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val host = glass.get() ?: return
        val source = scene.get() ?: return
        val frameSave = canvas.save()
        val inset = shadowInset
        canvas.translate(inset.toFloat(), inset.toFloat())
        val width = this.width - inset * 2
        val height = this.height - inset * 2
        val d = resources.displayMetrics.density
        val cfg = host.config
        val dark = host.adapterContentOrSystemDark()
        val posture = cfg.postureEffectsEnabled && cfg.liquidGlassEnabled && !cfg.solidBarEnabled
        PostureGlass.track(this, posture)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        shape.setBottomBarSquircle(rect, height / 2f, cfg.cornerSmoothing)
        ink.style = Paint.Style.FILL
        ink.color = Color.TRANSPARENT
        ink.setShadowLayer(8f * d, 0f, 2f * d, Color.argb(if (dark) 40 else 48, 0, 0, 0))
        val shadowSave = canvas.save()
        canvas.clipOutPath(shape)
        if (posture) PostureGlass.shadow(canvas, this, rect) { canvas.drawPath(shape, ink) }
        else canvas.drawPath(shape, ink)
        canvas.restoreToCount(shadowSave)
        ink.clearShadowLayer()
        val save = canvas.save()
        canvas.clipPath(shape)
        if (canvas.isHardwareAccelerated && cfg.backdropCapture && !cfg.solidBarEnabled) {
            val sourceNode = nodeField.get(source) as? RenderNode
            if (source !== parent && sourceNode?.hasDisplayList() == true) {
                val blur = cfg.blurRadius * d
                val pad = NativeBackdropBlur.padding(blur, 24f * d * cfg.refractionPaddingScale)
                val w = (width + pad * 2).toInt()
                val h = (height + pad * 2).toInt()
                getLocationInWindow(backdropPosition)
                source.getLocationInWindow(backdropOrigin)
                val x = pad + backdropOrigin[0] - backdropPosition[0] - inset - source.left
                val y = pad + backdropOrigin[1] - backdropPosition[1] - inset - source.top
                backdropInputs.set(0, w.toFloat())
                backdropInputs.set(1, h.toFloat())
                backdropInputs.set(2, x)
                backdropInputs.set(3, y)
                backdropInputs.set(4, if (dark) 1f else 0f)
                if (!node.hasDisplayList() || !backdropInputs.matches(sourceNode)) {
                    node.setPosition(0, 0, w, h)
                    val recording = node.beginRecording(w, h)
                    try {
                        recording.drawColor(if (dark) Color.rgb(24, 24, 24) else Color.WHITE)
                        recording.translate(x, y)
                        recording.drawRenderNode(sourceNode)
                    } finally { node.endRecording() }
                    backdropInputs.commit(sourceNode)
                }
                val key = "$width:$height:$blur:${cfg.liquidGlassEnabled}:${cfg.refractionStrength}"
                if (effectKey != key) {
                    lens.setFloatUniform("size", width.toFloat(), height.toFloat())
                    lens.setFloatUniform("offset", -pad, -pad)
                    lens.setFloatUniform("cornerRadii", FloatArray(4) { height / 2f })
                    lens.setFloatUniform("refractionHeight", 12f * d)
                    lens.setFloatUniform("refractionAmount", -24f * d * cfg.refractionScale)
                    lens.setFloatUniform("depthEffect", 0f)
                    val blurEffect = NativeBackdropBlur.effect(blur)
                    node.setRenderEffect(if (cfg.liquidGlassEnabled) RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(lens, "content"), blurEffect) else blurEffect)
                    effectKey = key
                }
                PostureGlass.sync(lens, this, posture, cfg.liquidViscosity,
                    cfg.flowHorizontalStrength, cfg.flowVerticalStrength)
                val sample = canvas.save()
                canvas.translate(-pad, -pad)
                canvas.drawRenderNode(node)
                canvas.restoreToCount(sample)
            }
        }
        ink.style = Paint.Style.FILL
        ink.color = if (planB) Color.argb(if (cfg.solidBarEnabled) 255 else 210, 7, 193, 96)
            else if (dark) Color.argb(if (cfg.solidBarEnabled) 255 else 100, 28, 28, 30)
            else Color.argb(if (cfg.solidBarEnabled) 255 else 100, 255, 255, 255)
        canvas.drawPath(shape, ink)
        if (isPressed) { ink.color = Color.argb(24, 127, 127, 127); canvas.drawPath(shape, ink) }
        canvas.restoreToCount(save)
        if (cfg.liquidGlassEnabled && cfg.outlineEnabled) host.drawAccessoryPanelHighlight(canvas, rect, height / 2f, shape)
        if (planB) {
            ink.style = Paint.Style.STROKE
            ink.color = Color.WHITE
            ink.strokeWidth = 2.8f * d
            ink.strokeCap = Paint.Cap.ROUND
            ink.strokeJoin = Paint.Join.ROUND
            val cx = width / 2f
            val cy = height / 2f
            val arrow = Path().apply {
                moveTo(cx - 7f * d, cy + 3f * d)
                lineTo(cx, cy - 4f * d)
                lineTo(cx + 7f * d, cy + 3f * d)
            }
            val arrowSave = canvas.save()
            canvas.rotate(arrowRotation, cx, cy)
            canvas.drawPath(arrow, ink)
            canvas.restoreToCount(arrowSave)
            ink.style = Paint.Style.FILL
            canvas.restoreToCount(frameSave)
            return
        }
        ink.style = Paint.Style.FILL
        ink.color = if (dark) Color.WHITE else Color.rgb(36, 36, 36)
        ink.textSize = 14f * resources.displayMetrics.scaledDensity
        val textWidth = ink.measureText(title)
        val textLeft = (width - textWidth - 18f * d) / 2f
        canvas.drawText(title, textLeft, height / 2f - (ink.ascent() + ink.descent()) / 2f, ink)
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 1.6f * d
        ink.strokeCap = Paint.Cap.ROUND
        val x = textLeft + textWidth + 12f * d
        val y = height / 2f
        val direction = if (title.contains("折叠")) 1f else -1f
        val arrow = Path().apply { moveTo(x - 3f * d, y + 2f * d * direction); lineTo(x, y - d * direction); lineTo(x + 3f * d, y + 2f * d * direction) }
        canvas.drawPath(arrow, ink)
        ink.style = Paint.Style.FILL
        canvas.restoreToCount(frameSave)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        if (planB) {
            if (togglePinnedChats?.invoke() != true) return false
            super.performClick()
            return true
        }
        val target = clickTarget?.takeIf { it.isAttachedToWindow && it.isEnabled } ?: return false
        super.performClick()
        beforeClick?.invoke()
        return target.performClick()
    }

    companion object {
        val planBInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.6f, 1f)

    fun findClickTarget(bar: View): View? {

        var current: View? = bar

        repeat(6) {

            val candidate = current ?: return null

            if (candidate.isAttachedToWindow && candidate.visibility == View.VISIBLE &&

                candidate.isEnabled && candidate.isClickable && candidate.hasOnClickListeners()

            ) {

                return candidate

            }

            current = candidate.parent as? View

        }

        return null

    }
    }

    var clickTarget: View? = null

    var beforeClick: (() -> Unit)? = null

    private var activeClickTarget: View? = null

    private var activeBeforeClick: (() -> Unit)? = null

    private var downX = 0f

    private var downY = 0f

    private var moved = false

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()



    init {

        isClickable = true
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        isFocusable = true

        background = null

        alpha = 0f
        visibility = INVISIBLE

    }



    override fun onTouchEvent(event: MotionEvent): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                val inset = shadowInset
                // Transparent shadow padding is not part of the hit target.
                if (event.x < inset || event.y < inset ||
                    event.x >= width - inset || event.y >= height - inset) return false

                if (planB) {
                    pressedPlanB = isEnabled && togglePinnedChats != null
                    downX = event.x
                    downY = event.y
                    moved = false
                    isPressed = pressedPlanB
                    return pressedPlanB
                }

                activeClickTarget = clickTarget?.takeIf {

                    it.isAttachedToWindow && it.visibility == View.VISIBLE && it.isEnabled

                }

                activeBeforeClick = beforeClick

                downX = event.x

                downY = event.y

                moved = false

                isPressed = activeClickTarget != null

                return activeClickTarget != null

            }

            MotionEvent.ACTION_MOVE -> {

                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {

                    moved = true

                    isPressed = false

                }

                return pressedPlanB || activeClickTarget != null

            }

            MotionEvent.ACTION_UP -> {
                if (pressedPlanB) {
                    pressedPlanB = false
                    isPressed = false
                    if (!moved) performClick()
                    return true
                }

                val target = activeClickTarget

                val shouldClick = isEnabled && !moved && target != null && target.isAttachedToWindow && target.isEnabled

                isPressed = false

                activeClickTarget = null

                val prepareNativeClick = activeBeforeClick

                activeBeforeClick = null

                moved = false

                if (shouldClick) {

                    prepareNativeClick?.invoke()

                    target.performClick()

                }

                return target != null

            }

            MotionEvent.ACTION_CANCEL -> {

                val handled = pressedPlanB || activeClickTarget != null
                pressedPlanB = false

                isPressed = false

                activeClickTarget = null

                activeBeforeClick = null

                moved = false

                return handled

            }

        }

        return activeClickTarget != null

    }

}
