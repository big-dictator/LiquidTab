package io.github.offlineglass.hook.adapters.meituan_main


import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.Surface
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.globalGlassBottomGapPx
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import io.github.offlineglass.rendering.NativeBackdropBlur
import java.lang.ref.WeakReference

/** Meituan cart uses the Taobao 48dp dual-capsule row. Native business
 * views remain the foreground and receive mapped native touch sequences. */
internal class MeituanMainCartLiquidButtons(context: Context, host: GlassHostLayout,
    private val source: ViewGroup, select: List<View>, amount: List<View>,
    checkout: List<View>, private val scene: View, private val eventRoot: ViewGroup) : View(context),
    io.github.offlineglass.hook.adapters.AdapterOwnedOverlay {
    private val host = WeakReference(host)
    private val rects = Array(2) { RectF() }
    private val paths = Array(2) { Path() }
    private val highlightPaths = Array(2) { Path() }
    private val highlights = Array(2) {
        runCatching { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() }.getOrNull()
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nodes = Array(2) { RenderNode("MeituanCartCapsule$it") }
    private val lenses = Array(2) { io.github.offlineglass.rendering.PostureGlass.shader(KYANT_LENS_SHADER, flowing = false) }
    private val effects = arrayOfNulls<String>(2)
    private val recordings = arrayOfNulls<String>(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ambient = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val location = IntArray(2)
    private val hostLocation = IntArray(2)
    private val parentLocation = IntArray(2)
    private var geometry = ""
    private val targets = Array(3) { RectF() }
    private val nativeViews = arrayOf(select, amount, checkout)
    private val contentRects = Array(3) { RectF() }
    private val sourcePosition = IntArray(2)
    private val scenePosition = IntArray(2)
    private val sceneNode = runCatching { View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }.get(scene) as? RenderNode }.getOrNull()
    private var downAt = 0L
    private var downKind: String? = null
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var businessTouchEnabled = true

    fun setBusinessTouchEnabled(enabled: Boolean) {
        if (businessTouchEnabled && !enabled) {
            downKind?.toIntOrNull()?.let { send(it, MotionEvent.ACTION_CANCEL, downX, downY) }
            downKind = null
        }
        businessTouchEnabled = enabled
    }

    init { setWillNotDraw(false); isClickable = true; updateContentBounds() }

    private fun updateContentBounds() {
        nativeViews.forEachIndexed { index, views ->
            val bounds = contentRects[index]
            bounds.setEmpty()
            for (view in views) {
                view.getLocationInWindow(sourcePosition)
                bounds.union(sourcePosition[0].toFloat(), sourcePosition[1].toFloat(),
                    (sourcePosition[0] + view.width).toFloat(), (sourcePosition[1] + view.height).toFloat())
            }
        }
    }

    fun capsuleTopInWindow(): Int {
        getLocationInWindow(location)
        return (location[1] + rects[0].top).toInt()
    }

    fun validBinding(): Boolean = source.isAttachedToWindow &&
        nativeViews.all { views -> views.isNotEmpty() && views.all { it.isAttachedToWindow } }

    fun refresh() {
        updateContentBounds()
        updateGeometry()
        if (scene.isDirty || source.isDirty || nativeViews.any { views -> views.any { it.isDirty } }) invalidate()
    }

    fun updateGeometry() {
        val glass = host.get() ?: return
        glass.getLocationInWindow(hostLocation)
        val h = 48f * resources.displayMetrics.density
        (parent as? View)?.getLocationInWindow(parentLocation) ?: return
        // Limit invalidation to this padded button row, rather than damaging the entire
        // cart window every time its sampled backdrop changes.
        val wantedY = hostLocation[1] - parentLocation[1] - globalGlassBottomGapPx(glass) / 2f - h - 20f * resources.displayMetrics.density
        if (translationY != wantedY) translationY = wantedY
        getLocationInWindow(location)
        val bottom = hostLocation[1] - location[1] - globalGlassBottomGapPx(glass) / 2f
        val left = (hostLocation[0] - location[0]).toFloat()
        val right = left + glass.width
        val button = resources.displayMetrics.widthPixels * .28f
        val gap = resources.displayMetrics.widthPixels * .0213333f
        val blur = glass.config.blurRadius
        val key = "$left:$right:$bottom:$h:$blur:${glass.adapterContentOrSystemDark()}"
        if (geometry == key) return
        geometry = key
        rects[0].set(left, bottom - h, right - button - gap, bottom)
        rects[1].set(right - button, bottom - h, right, bottom)
        for (i in paths.indices) {
            paths[i].setBottomBarSquircle(rects[i], h / 2f, -1f)
            highlightPaths[i].setBottomBarSquircle(RectF(0f, 0f, rects[i].width(), h), h / 2f, -1f)
        }
        val leftRect = rects[0]
        val selectWidth = minOf(contentRects[0].width().takeIf { it > 0 }
            ?: (72f * resources.displayMetrics.density), leftRect.width() * .32f)
        targets[0].set(leftRect.left + 12f * resources.displayMetrics.density,
            leftRect.top, leftRect.left + 12f * resources.displayMetrics.density + selectWidth, leftRect.bottom)
        targets[1].set(targets[0].right + 4f * resources.displayMetrics.density,
            leftRect.top, leftRect.right - 12f * resources.displayMetrics.density, leftRect.bottom)
        targets[2].set(rects[1])
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val glass = host.get() ?: return
        if (glass.adapterSelectedIndex != 3) return
        updateGeometry()
        if (rects.any { it.width() <= 0 || it.height() <= 0 }) return
        val density = resources.displayMetrics.density
        val dark = false
        val postureEnabled = glass.config.postureEffectsEnabled && glass.config.liquidGlassEnabled &&
            !glass.config.solidBarEnabled
        // Register this independently-drawn control with the shared sensor so
        // it receives frame invalidations without moving its screen-space layers.
        io.github.offlineglass.rendering.PostureGlass.track(this, postureEnabled)
        val blur = glass.config.blurRadius * density
        getLocationInWindow(location)
        for (i in rects.indices) {
            val rect = rects[i]
            val path = paths[i]
            ambient.setShadowLayer(10f * density, 0f, 0f,
                Color.argb(if (dark) 36 else 48, 0, 0, 0))
            var outer = canvas.save()
            canvas.clipOutPath(path)
            if (glass.config.postureEffectsEnabled && glass.config.liquidGlassEnabled && !glass.config.solidBarEnabled)
                io.github.offlineglass.rendering.PostureGlass.shadow(canvas, this, rect) {
                    canvas.drawPath(path, ambient)
                } else canvas.drawPath(path, ambient)
            canvas.restoreToCount(outer)
            if (dark) {
                ambient.setShadowLayer(18f * density, 0f, 0f, Color.argb(14, 220, 235, 255))
                outer = canvas.save()
                canvas.clipOutPath(path)
                if (glass.config.postureEffectsEnabled && glass.config.liquidGlassEnabled && !glass.config.solidBarEnabled)
                io.github.offlineglass.rendering.PostureGlass.shadow(canvas, this, rect) {
                    canvas.drawPath(path, ambient)
                } else canvas.drawPath(path, ambient)
                canvas.restoreToCount(outer)
            }
            val save = canvas.save()
            canvas.clipPath(path)
            if (glass.config.backdropCapture && !glass.config.solidBarEnabled &&
                (glass.config.liquidGlassEnabled || blur > 0f) &&
                canvas.isHardwareAccelerated && sceneNode?.hasDisplayList() == true) {
                val pad = NativeBackdropBlur.padding(blur, 24f * density * glass.config.refractionPaddingScale)
                val w = (rect.width() + pad * 2).toInt()
                val h = (rect.height() + pad * 2).toInt()
                val node = nodes[i]
                node.setPosition(0, 0, w, h)
                scene.getLocationInWindow(scenePosition)
                val record = "$rect:$pad:${scenePosition[0]-location[0]}:${scenePosition[1]-location[1]}"
                if (recordings[i] != record || !node.hasDisplayList()) {
                    val recording = node.beginRecording(w, h)
                    try {
                        recording.drawColor(if (dark) 0xFF121215.toInt() else Color.WHITE)
                        recording.translate(pad - rect.left + scenePosition[0] - location[0],
                            pad - rect.top + scenePosition[1] - location[1])
                        // This is the native MSC page only. It contains neither
                        // our overlay nor the navigation Surface; no readback loop.
                        recording.drawRenderNode(sceneNode)
                    } finally { node.endRecording() }
                    recordings[i] = record
                }
                val effect = "$w:$h:$blur:$density:${glass.config.liquidGlassEnabled}:${glass.config.refractionStrength}"
                if (effects[i] != effect) {
                    val lens = lenses[i]
                    lens.setFloatUniform("size", rect.width(), rect.height())
                    lens.setFloatUniform("offset", -pad, -pad)
                    lens.setFloatUniform("cornerRadii", FloatArray(4) { rect.height() / 2f })
                    // Kyant LiquidButton reference: 48dp high, lens(12dp, 24dp).
                    lens.setFloatUniform("refractionHeight", 12f * density)
                    lens.setFloatUniform("refractionAmount", -24f * density * glass.config.refractionScale)
                    lens.setFloatUniform("depthEffect", 0f)
                    val blurEffect = NativeBackdropBlur.effect(blur)
                    node.setRenderEffect(if (glass.config.liquidGlassEnabled)
                        RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), blurEffect)
                        else blurEffect)
                    effects[i] = effect
                }
                // RenderEffect retains this shader instance. Updating the
                // sampling vector in place avoids rebuilding the GPU effect.
                io.github.offlineglass.rendering.PostureGlass.sync(lenses[i], this, postureEnabled,
                    glass.config.liquidViscosity, glass.config.flowHorizontalStrength, glass.config.flowVerticalStrength)
                val translated = canvas.save()
                canvas.translate(rect.left - pad, rect.top - pad)
                canvas.drawRenderNode(node)
                canvas.restoreToCount(translated)
            }
            paint.style = Paint.Style.FILL
            paint.color = if (i == 1) Color.argb(235, 255, 195, 0)
                else if (dark) Color.argb(184, 28, 28, 30) else Color.argb(184, 255, 255, 255)
            canvas.drawRect(rect, paint)
            canvas.restoreToCount(save)
            if (!glass.config.liquidGlassEnabled || glass.config.solidBarEnabled) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .6f * density
                paint.color = Color.argb(if (dark) 30 else 90, 255, 255, 255)
                canvas.drawPath(path, paint)
            }
            val light = canvas.save()
            canvas.clipPath(path)
            drawHighlight(canvas, i, glass, density)
            canvas.restoreToCount(light)
        }
        // Foreground is drawn AFTER both lens effects, once.
        if (!validBinding() || !source.isShown) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.3f * density
            paint.color = if (dark) 0xFFB8B8BD.toInt() else 0xFF77777C.toInt()
            canvas.drawCircle(rects[0].left + 20f * density, rects[0].centerY(), 9f * density, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 14f * density
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText("全选", rects[0].left + 36f * density, rects[0].centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.textSize = 12f * density
            canvas.drawText("计算中…", rects[0].right - 12f * density, rects[0].centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            paint.color = Color.WHITE
            paint.textSize = 16f * density
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("去结算", rects[1].centerX(), rects[1].centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            return
        }
        nativeViews.forEachIndexed { index, views ->
            val target = targets[index]
            if (!contentRects[index].isEmpty && target.width() > 0f) {
                val content = contentRects[index]
                val scale = minOf(1f, target.width() / content.width().coerceAtLeast(1f), target.height() / content.height().coerceAtLeast(1f))
                val saved = canvas.save()
                canvas.clipRect(target)
                val x = if (index == 0) target.left else if (index == 1)
                    target.right - content.width() * scale else target.centerX() - content.width() * scale / 2f
                canvas.translate(x, target.centerY() - content.height() * scale / 2f)
                canvas.scale(scale, scale)
                canvas.translate(-content.left, -content.top)
                for (view in views) {
                    view.getLocationInWindow(sourcePosition)
                    val foreground = canvas.save()
                    canvas.translate(sourcePosition[0].toFloat(), sourcePosition[1].toFloat())
                    drawNativeLeaves(canvas, view)
                    canvas.restoreToCount(foreground)
                }
                canvas.restoreToCount(saved)
            }
        }
    }

    /** UI-owned chrome: never touch the host's worker-owned shader, Paint or Path. */
    private fun drawHighlight(canvas: Canvas, index: Int, glass: GlassHostLayout, density: Float) {
        if (!glass.config.liquidGlassEnabled || !glass.config.outlineEnabled || glass.config.solidBarEnabled) return
        val shader = highlights[index] ?: return
        val rect = rects[index]
        var gx = if (glass.config.postureEffectsEnabled) BloomTiltTracker.gravityX else 0f
        var gy = if (glass.config.postureEffectsEnabled) BloomTiltTracker.gravityY else 0f
        when (display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> { val x = gx; gx = -gy; gy = x }
            Surface.ROTATION_180 -> { gx = -gx; gy = -gy }
            Surface.ROTATION_270 -> { val x = gx; gx = gy; gy = -x }
        }
        val magnitude = gx * gx + gy * gy
        if (magnitude > BloomTiltTracker.GRAVITY_DIR_THRESHOLD_SQ) {
            val inverse = 1f / kotlin.math.sqrt(magnitude); gx *= inverse; gy *= inverse
        } else { gx = 0f; gy = -1f }
        val c = .70710677f
        val dx = c * gx + c * gy
        val dy = -c * gx + c * gy
        val length = kotlin.math.sqrt(dx * dx + dy * dy + .0025f)
        shader.setFloatUniform("halfView", rect.width() / 2f, rect.height() / 2f)
        shader.setFloatUniform("highlightAlpha", (.58f * glass.config.darkBarHighlightStrength).coerceIn(0f, 1f))
        shader.setColorUniform("strokeColor", Color.WHITE)
        shader.setFloatUniform("strokeAlphaMul", .12f)
        shader.setFloatUniform("lightDir1", dx / length, dy / length, -.05f / length)
        shader.setColorUniform("lightColor1", Color.WHITE)
        shader.setFloatUniform("lightIntensity1", 1f)
        val secondaryLength = kotlin.math.sqrt(.26f)
        shader.setFloatUniform("lightDir2", 0f, .1f / secondaryLength, -.5f / secondaryLength)
        shader.setColorUniform("lightColor2", Color.WHITE)
        shader.setFloatUniform("lightIntensity2", .4f)
        highlightPaint.shader = shader
        highlightPaint.strokeWidth = density
        val saved = canvas.save()
        canvas.translate(rect.left, rect.top)
        io.github.offlineglass.rendering.GlassEdgeHighlight.draw(
            canvas, highlightPaths[index], highlightPaint, shader, density, glass.adapterContentOrSystemDark(),
            glass.config.darkBarHighlightStrength > 0f)
        canvas.restoreToCount(saved)
        highlightPaint.shader = null
    }

    // Container backgrounds belong to the hidden original footer. Only the
    // real leaf controls/text are foreground, so native styles stay untouched.
    private fun drawNativeLeaves(canvas: Canvas, view: View) {
        if (view.visibility != VISIBLE) return
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val save = canvas.save()
                canvas.translate(child.left + child.translationX, child.top + child.translationY)
                drawNativeLeaves(canvas, child)
                canvas.restoreToCount(save)
            }
        } else view.draw(canvas)
    }

    private fun targetAt(x: Float, y: Float): Int = when {
        rects[1].contains(x, y) -> 2
        rects[0].contains(x, y) -> if (x < targets[0].right) 0 else 1
        else -> -1
    }

    private fun send(index: Int, action: Int, x: Float, y: Float) {
        if (!validBinding() || !source.isShown) return
        val view = nativeViews[index].first()
        view.getLocationInWindow(location)
        eventRoot.getLocationInWindow(sourcePosition)
        val px = location[0] - sourcePosition[0] + view.width / 2f
        val py = location[1] - sourcePosition[1] + view.height / 2f
        val event = MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, px, py, 0)
        // Meituan 12.66.404: render.rn.i inherits MSC ReactRootView;
        // onInterceptTouchEvent invokes dispatchJSTouchEvent. The flattened
        // footer's leaf performClick would bypass the MSC business handler.
        try { eventRoot.onInterceptTouchEvent(event) } finally { event.recycle() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!businessTouchEnabled) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = targetAt(event.x, event.y)
                if (index < 0) return false
                downKind = index.toString(); downX = event.x; downY = event.y
                downAt = event.downTime
                send(index, MotionEvent.ACTION_DOWN, event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                    downKind?.toIntOrNull()?.let { send(it, MotionEvent.ACTION_CANCEL, event.x, event.y) }
                    downKind = null
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                downKind?.toIntOrNull()?.let {
                    send(it, if (event.actionMasked == MotionEvent.ACTION_UP && targetAt(event.x, event.y) == it)
                        MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL, event.x, event.y)
                }
                downKind = null
                postInvalidateOnAnimation()
                return true
            }
        }
        return downKind != null
    }

    fun release() {
        io.github.offlineglass.rendering.PostureGlass.track(this, false)
        nodes.forEach { it.discardDisplayList() }
    }
}

