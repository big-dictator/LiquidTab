package io.github.offlineglass.hook.adapters.jd


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

/** JD cart geometry matches the Taobao 48dp dual-capsule row. Native business
 * views remain the foreground and receive mapped native touch sequences. */
internal class JdCartLiquidButtons(context: Context, host: GlassHostLayout,
    private val binding: JdCheckoutBinding, private val sceneProvider: () -> View?) : View(context),
    io.github.offlineglass.hook.adapters.AdapterOwnedOverlay {
    private val host = WeakReference(host)
    private val rects = Array(2) { RectF() }
    private val paths = Array(2) { Path() }
    private val highlightPaths = Array(2) { Path() }
    private val highlights = Array(2) {
        runCatching { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() }.getOrNull()
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val nodes = Array(2) { RenderNode("JdCartCapsule$it") }
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
    private val nativeViews = arrayOf(binding.select, binding.amount, binding.checkout)
    private val contentRects = Array(3) { RectF() }
    private val sourcePosition = IntArray(2)
    private val scenePosition = IntArray(2)
    private val renderNodeField = runCatching { View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true } }.getOrNull()
    private var sampledScene: View? = null
    private var sceneNode: RenderNode? = null
    private var downAt = 0L
    private var downKind: String? = null
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { setWillNotDraw(false); isClickable = true; updateContentBounds() }

    override fun toString(): String = super.toString() + " cartBackdrop=${sampledScene?.javaClass?.name ?: "theme"}"

    /** React may paint the select-all ring on a small ViewGroup itself rather
     * than an ImageView leaf. Keep that native glyph intact, without copying
     * the large footer/container backgrounds into the glass capsule. */
    private fun isSelectionGlyph(view: View): Boolean {
        if (view !is ViewGroup) return false
        val limit = 32f * resources.displayMetrics.density
        return view.width > 0 && view.height > 0 &&
            view.width <= limit && view.height <= limit &&
            view.width >= view.height * .75f && view.width <= view.height * 1.33f
    }

    private fun updateContentBounds() {
        nativeViews.forEachIndexed { index, root ->
            val bounds = contentRects[index]
            bounds.setEmpty()
            fun collect(view: View, x: Float, y: Float) {
                if (view.visibility != VISIBLE) return
                if (view !is ViewGroup || (index == 0 && isSelectionGlyph(view))) {
                    if (view.width > 0 && view.height > 0)
                        bounds.union(x, y, x + view.width, y + view.height)
                } else {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        collect(child, x + child.left + child.translationX, y + child.top + child.translationY)
                    }
                }
            }
            collect(root, 0f, 0f)
            if (bounds.isEmpty) bounds.set(0f, 0f, root.width.toFloat(), root.height.toFloat())
        }
    }

    fun capsuleTopInWindow(): Int {
        getLocationInWindow(location)
        return (location[1] + rects[0].top).toInt()
    }

    fun refresh() {
        if (nativeViews.any { it.isDirty }) updateContentBounds()
        updateGeometry()
        // Native mutations and scrolling invalidate the scene, not an idle timer.
        if (sceneProvider()?.isDirty == true) invalidate()
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
        val selectWidth = minOf(nativeViews[0].width.takeIf { it > 0 }?.toFloat()
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
        if (!binding.isCurrent()) return
        val scene = sceneProvider()?.takeIf { it.isAttachedToWindow && it.isShown }
        if (sampledScene !== scene) {
            sampledScene = scene
            sceneNode = scene?.let { runCatching { renderNodeField?.get(it) as? RenderNode }.getOrNull() }
            recordings.fill(null)
            nodes.forEach { it.discardDisplayList() }
        }
        val inputNode = sceneNode
        updateGeometry()
        if (rects.any { it.width() <= 0 || it.height() <= 0 }) return
        val density = resources.displayMetrics.density
        val dark = glass.adapterContentOrSystemDark()
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
            // No cart frame yet: never let an unrelated live page bleed through.
            if (scene == null) canvas.drawColor(if (dark) Color.rgb(32, 32, 32) else Color.WHITE)
            if (glass.config.backdropCapture && !glass.config.solidBarEnabled &&
                (glass.config.liquidGlassEnabled || blur > 0f) &&
                canvas.isHardwareAccelerated && scene != null && inputNode?.hasDisplayList() == true) {
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
                        // This is the native Taro page only. It contains neither
                        // our overlay nor the navigation Surface; no readback loop.
                        recording.drawRenderNode(inputNode)
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
            paint.color = if (i == 1) Color.argb(184, 226, 35, 26)
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
        nativeViews.forEachIndexed { index, view ->
            val target = targets[index]
            if (view.width > 0 && view.height > 0 && target.width() > 0f) {
                val content = contentRects[index]
                val scale = minOf(1f, target.width() / content.width().coerceAtLeast(1f), target.height() / content.height().coerceAtLeast(1f))
                val saved = canvas.save()
                canvas.clipRect(target)
                val x = if (index == 0) target.left else if (index == 1)
                    target.right - content.width() * scale else target.centerX() - content.width() * scale / 2f
                canvas.translate(x, target.centerY() - content.height() * scale / 2f)
                canvas.scale(scale, scale)
                canvas.translate(-content.left, -content.top)
                drawNativeLeaves(canvas, view, includeSelectionGlyphs = index == 0)
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
    private fun drawNativeLeaves(canvas: Canvas, view: View, includeSelectionGlyphs: Boolean = false) {
        if (view.visibility != VISIBLE) return
        if (view is ViewGroup && !(includeSelectionGlyphs && isSelectionGlyph(view))) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val save = canvas.save()
                canvas.translate(child.left + child.translationX, child.top + child.translationY)
                drawNativeLeaves(canvas, child, includeSelectionGlyphs)
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
        if (!binding.isCurrent()) return
        val eventRoot = binding.eventRoot
        val view = nativeViews[index]
        val target = targets[index]
        val content = contentRects[index]
        val scale = minOf(1f, target.width() / content.width().coerceAtLeast(1f), target.height() / content.height().coerceAtLeast(1f))
        val originX = if (index == 0) target.left else if (index == 1)
            target.right - content.width() * scale else target.centerX() - content.width() * scale / 2f
        val originY = target.centerY() - content.height() * scale / 2f
        view.getLocationInWindow(location)
        eventRoot.getLocationInWindow(sourcePosition)
        val px = ((x - originX) / scale + content.left).coerceIn(1f, (view.width - 1).coerceAtLeast(1).toFloat()) + location[0] - sourcePosition[0]
        val py = ((y - originY) / scale + content.top).coerceIn(1f, (view.height - 1).coerceAtLeast(1).toFloat()) + location[1] - sourcePosition[1]
        val event = MotionEvent.obtain(downAt, android.os.SystemClock.uptimeMillis(), action, px, py, 0)
        // The React root dispatches the mapped touch and pointer events to
        // the original checkout handlers, including on ReactSurfaceView.
        try { eventRoot.onInterceptTouchEvent(event) } finally { event.recycle() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!binding.isCurrent()) return false
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
        downKind?.toIntOrNull()?.let { send(it, MotionEvent.ACTION_CANCEL, downX, downY) }
        downKind = null
        io.github.offlineglass.rendering.PostureGlass.track(this, false)
        nodes.forEach { it.discardDisplayList() }
        sampledScene = null
        sceneNode = null
    }
}
