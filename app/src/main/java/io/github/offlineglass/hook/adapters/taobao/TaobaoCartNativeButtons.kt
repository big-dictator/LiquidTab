package io.github.offlineglass.hook.adapters.taobao


import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Surface
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.globalGlassBottomGapPx
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import java.lang.ref.WeakReference

/** Glass samples the engine with its footer transparent. All glyphs and check
 * state are a separate native foreground, never included in the lens input. */
internal class TaobaoCartNativeButtons(context: Context, host: GlassHostLayout,
    private var action: (String, String) -> Unit) : View(context), io.github.offlineglass.hook.adapters.AdapterOwnedOverlay {
    private var inputEnabled=true
    fun setInputEnabled(enabled:Boolean){inputEnabled=enabled;if(!enabled)downKind=null}
    fun rebindAction(callback:(String,String)->Unit){action=callback}
    data class State(val title: String, val amount: String, val discount: String,
        val checkout: String, val subtitle: String, val allText: String,
        val checked: Boolean, val available: Boolean, val showSelect: Boolean) {
        companion object {
            fun from(map: Map<*, *>): State? = runCatching {
                fun text(key: String) = (map[key] as? String).orEmpty().take(160)
                State(text("title"), text("amount"), text("discount"), text("checkout"),
                    text("subtitle"), text("allText"), map["checked"] == true,
                    map["available"] == true, map["showSelect"] != false)
            }.getOrNull()
        }
    }
    private val host = WeakReference(host)
    fun belongsTo(value:GlassHostLayout):Boolean=host.get()===value
    private var state: State? = null
    private val rects = Array(2) { RectF() }
    private val paths = Array(2) { Path() }
    private val highlightPaths = Array(2) { Path() }
    private val highlights = Array(2) {
        runCatching { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() }.getOrNull()
    }
    private val highlightUniforms = Array(2) { index -> highlights[index]?.let {
        io.github.offlineglass.rendering.ExactOpticalUniformCache(it)
    } }
    private val geometryWork = io.github.offlineglass.rendering.ExactOpticalGeometryCache()
    private val foregroundNode = RenderNode("TaobaoCartForeground")
    private data class ForegroundKey(val state: State, val dark: Boolean, val density: Float,
        val scaledDensity: Float, val geometry: GeometryKey?, val width: Int, val height: Int)
    private var foregroundKey: ForegroundKey? = null
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sharedBlurNode = RenderNode("TaobaoCartSharedBlur")
    private val nodes = Array(2) { RenderNode("TaobaoCleanCartButton$it") }
    private val lenses = Array(2) { io.github.offlineglass.rendering.PostureGlass.shader(KYANT_LENS_SHADER, flowing = false) }
    private data class EffectKey(val width:Int,val height:Int,val blur:Float,val density:Float,val refraction:Float)
    private data class RecordingKey(val left:Float,val top:Float,val right:Float,val bottom:Float,
        val sourceLeft:Int,val sourceTop:Int,val sourceWidth:Int,val sourceHeight:Int,
        val bitmapWidth:Int,val bitmapHeight:Int,val pad:Float,val dark:Boolean)
    private val effects = arrayOfNulls<EffectKey>(2)
    private val recordings = arrayOfNulls<RecordingKey>(2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ambient = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private var shadowDensity = Float.NaN
    private var shadowDark = false
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = Path()
    private val location = IntArray(2)
    private val hostLocation = IntArray(2)
    private val parentLocation = IntArray(2)
    private val sceneLocation = IntArray(2)
    private data class SceneKey(val node: RenderNode, val x: Int, val y: Int,
        val width: Int, val height: Int, val blur: Float)
    private var sceneKey: SceneKey? = null
    fun onNativeContentFrame() {
        sceneKey = null
        recordings.fill(null)
        invalidate()
    }

    private fun prepareScene(glass: GlassHostLayout, density: Float): Boolean {
        if (!glass.config.backdropCapture || glass.config.solidBarEnabled) return false
        val blur = (geometry?.blur ?: 0f) * density
        if (!glass.config.liquidGlassEnabled && blur <= 0f) return false
        val root = (glass.appNavigationState as? TaobaoNavigationState)?.resolveBackdropScene(this) ?: return false
        val node = glass.retainedBackdropNodeForAdapter(root) ?: return false
        root.getLocationInWindow(sceneLocation)
        val key = SceneKey(node, sceneLocation[0] - location[0] - node.left,
            sceneLocation[1] - location[1] - node.top, width, height, blur)
        if (key != sceneKey || !sharedBlurNode.hasDisplayList()) {
            val recording = sharedBlurNode.beginRecording(width, height)
            try {
                recording.translate(key.x.toFloat(), key.y.toFloat())
                recording.drawRenderNode(node)
            } finally { sharedBlurNode.endRecording() }
            sharedBlurNode.setPosition(0, 0, width, height)
            sharedBlurNode.setRenderEffect(if (blur > 0f)
                RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) else null)
            sceneKey = key
        }
        return true
    }

    private fun drawScene(canvas: Canvas, i: Int, glass: GlassHostLayout, density: Float) {
        val rect = rects[i]
        val lens = lenses[i]
        if (!glass.config.liquidGlassEnabled || lens == null) {
            canvas.drawRenderNode(sharedBlurNode)
            return
        }
        val w = kotlin.math.ceil(rect.width()).toInt()
        val h = kotlin.math.ceil(rect.height()).toInt()
        val pad = (24f * density).toInt()
        val key = EffectKey(w, h, 0f, density, 1f)
        val node = nodes[i]
        val recordingKey = RecordingKey(rect.left, rect.top, rect.right, rect.bottom,
            0, 0, width, height, 0, 0, pad.toFloat(), false)
        if (recordings[i] != recordingKey || !node.hasDisplayList()) {
            val recording = node.beginRecording(w + 2 * pad, h + 2 * pad)
            try {
                recording.translate(pad - rect.left, pad - rect.top)
                recording.drawRenderNode(sharedBlurNode)
            } finally { node.endRecording() }
            node.setPosition(0, 0, w + 2 * pad, h + 2 * pad)
            recordings[i] = recordingKey
        }
        if (effects[i] != key) {
            lens.setFloatUniform("size", rect.width(), rect.height())
            lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
            lens.setFloatUniform("cornerRadii", FloatArray(4) { rect.height() / 2f })
            lens.setFloatUniform("refractionHeight", 12f * density)
            lens.setFloatUniform("refractionAmount", -24f * density)
            lens.setFloatUniform("depthEffect", 0f)
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"))
            effects[i] = key
        }
        val save = canvas.save()
        canvas.translate(rect.left - pad, rect.top - pad)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(save)
    }
    private data class GeometryKey(val left:Float,val right:Float,val bottom:Float,val height:Float,
        val blur:Float,val dark:Boolean)
    private var geometry:GeometryKey? = null
    @Volatile private var cleanBackdrop = false
    private var downKind: String? = null
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { setWillNotDraw(false); isClickable = true }

    fun update(value: State) {
        if (state != value) { state = value; invalidate() }
        updateGeometry()
        contentDescription = "${value.allText}，${value.title}${value.amount}，${value.checkout}"
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
        val blur = (glass.appNavigationState as? TaobaoNavigationState)?.cartButtonBlurRadius(glass)
            ?: glass.config.blurRadius
        val key = GeometryKey(left,right,bottom,h,blur,glass.adapterContentOrSystemDark())
        if (geometry == key) return
        geometry = key
        rects[0].set(left, bottom - h, right - button - gap, bottom)
        rects[1].set(right - button, bottom - h, right, bottom)
        for (i in paths.indices) {
            geometryWork.copyInto(paths[i], rects[i], h / 2f, -1f)
            geometryWork.copyInto(highlightPaths[i], RectF(0f, 0f, rects[i].width(), h), h / 2f, -1f)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // A software backdrop capture must not record these foreground controls
        // or feed their pixels back into either glass surface.
        if (!canvas.isHardwareAccelerated) return
        android.os.Trace.beginSection("LiquidTab.CartButtons.draw")
        try { drawButtonFrame(canvas) } finally { android.os.Trace.endSection() }
    }
    private fun drawButtonFrame(canvas: Canvas) {
        val glass = host.get() ?: return
        val data = state ?: return
        if ((glass.appNavigationState as? TaobaoNavigationState)?.cartPageActive() != true || !data.available) return
        updateGeometry()
        if (rects.any { it.width() <= 0 || it.height() <= 0 }) return
        val density = resources.displayMetrics.density
        val dark = glass.adapterContentOrSystemDark()
        if(shadowDensity!=density||shadowDark!=dark){
            ambient.setShadowLayer(10f*density,0f,0f,Color.argb(if(dark)36 else 48,0,0,0))
            glow.setShadowLayer(18f*density,0f,0f,Color.argb(14,220,235,255))
            shadowDensity=density;shadowDark=dark
        }
        val sceneReady = prepareScene(glass, density)
        for (i in rects.indices) {
            val rect = rects[i]
            val path = paths[i]
            var outer = canvas.save()
            canvas.clipOutPath(path)
            if (glass.config.postureEffectsEnabled && glass.config.liquidGlassEnabled && !glass.config.solidBarEnabled)
                io.github.offlineglass.rendering.PostureGlass.shadow(canvas, this, rect) {
                    canvas.drawPath(path, ambient)
                } else canvas.drawPath(path, ambient)
            canvas.restoreToCount(outer)
            if (dark) {
                outer = canvas.save()
                canvas.clipOutPath(path)
                if (glass.config.postureEffectsEnabled && glass.config.liquidGlassEnabled && !glass.config.solidBarEnabled)
                io.github.offlineglass.rendering.PostureGlass.shadow(canvas, this, rect) {
                    canvas.drawPath(path, glow)
                } else canvas.drawPath(path, glow)
                canvas.restoreToCount(outer)
            }
            val save = canvas.save()
            canvas.clipPath(path)
            if (sceneReady) drawScene(canvas, i, glass, density)
            paint.style = Paint.Style.FILL
            paint.color = if (i == 1) Color.argb(184, 255, 102, 0)
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
        if (canvas.isHardwareAccelerated && width > 0 && height > 0) {
            val key = ForegroundKey(data, dark, density, resources.displayMetrics.scaledDensity,
                geometry, width, height)
            if (key != foregroundKey || !foregroundNode.hasDisplayList()) {
                val recording = foregroundNode.beginRecording(width, height)
                try {
                    drawInformation(recording, data, dark, density)
                    drawCheckout(recording, data, density)
                } finally { foregroundNode.endRecording() }
                foregroundNode.setPosition(0, 0, width, height)
                foregroundKey = key
            }
            canvas.drawRenderNode(foregroundNode)
        } else {
            drawInformation(canvas, data, dark, density)
            drawCheckout(canvas, data, density)
        }
    }

    /** UI-owned chrome: never touch the host's worker-owned shader, Paint or Path. */
    private fun drawHighlight(canvas: Canvas, index: Int, glass: GlassHostLayout, density: Float) {
        if (!glass.config.liquidGlassEnabled || !glass.config.outlineEnabled || glass.config.solidBarEnabled) return
        val shader = highlights[index] ?: return
        val uniforms = highlightUniforms[index] ?: return
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
        uniforms.floats("halfView", rect.width() / 2f, rect.height() / 2f)
        uniforms.floats("highlightAlpha", (.58f * glass.config.darkBarHighlightStrength).coerceIn(0f, 1f))
        uniforms.color("strokeColor", Color.WHITE)
        uniforms.floats("strokeAlphaMul", .12f)
        uniforms.floats("lightDir1", dx / length, dy / length, -.05f / length)
        uniforms.color("lightColor1", Color.WHITE)
        uniforms.floats("lightIntensity1", 1f)
        val secondaryLength = kotlin.math.sqrt(.26f)
        uniforms.floats("lightDir2", 0f, .1f / secondaryLength, -.5f / secondaryLength)
        uniforms.color("lightColor2", Color.WHITE)
        uniforms.floats("lightIntensity2", .4f)
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

    private fun baseline(y: Float) = y - (ink.fontMetrics.ascent + ink.fontMetrics.descent) / 2f
    private fun drawInformation(canvas: Canvas, data: State, dark: Boolean, density: Float) {
        val r = rects[0]
        val cx = r.left + r.height() / 2f
        val cy = r.centerY()
        if (data.showSelect) {
        paint.style = if (data.checked) Paint.Style.FILL else Paint.Style.STROKE
        paint.strokeWidth = 1.5f * density
        paint.color = if (data.checked) Color.rgb(255, 102, 0) else if (dark) 0xFFB6BAC0.toInt() else 0xFF8A8D92.toInt()
        canvas.drawCircle(cx, cy, 11f * density, paint)
        if (data.checked) {
            check.reset(); check.moveTo(cx - 5f * density, cy)
            check.lineTo(cx - density, cy + 4f * density); check.lineTo(cx + 6f * density, cy - 5f * density)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * density; paint.color = Color.WHITE
            canvas.drawPath(check, paint)
        }
        ink.typeface = Typeface.DEFAULT
        ink.color = if (dark) Color.WHITE else 0xFF25282D.toInt()
        ink.textSize = 12f * resources.displayMetrics.scaledDensity
        ink.textAlign = Paint.Align.LEFT
        canvas.drawText(data.allText, r.left + r.height(), baseline(cy), ink)
        }
        ink.typeface = Typeface.DEFAULT
        val right = r.right - 12f * density
        ink.textAlign = Paint.Align.RIGHT
        val y = if (data.discount.isEmpty()) cy else cy - 5f * density
        ink.textSize = 16f * resources.displayMetrics.scaledDensity
        ink.color = Color.rgb(255, 102, 0)
        val amount = data.amount
        val amountWidth = ink.measureText(amount)
        canvas.drawText(amount, right, baseline(y), ink)
        ink.textSize = 12f * resources.displayMetrics.scaledDensity
        ink.color = if (dark) Color.WHITE else 0xFF25282D.toInt()
        canvas.drawText(data.title, right - amountWidth - 2f * density, baseline(y), ink)
        if (data.discount.isNotEmpty()) {
            ink.textSize = 9f * resources.displayMetrics.scaledDensity
            ink.color = Color.rgb(255, 102, 0)
            canvas.drawText(data.discount, right, baseline(cy + 11f * density), ink)
        }
    }

    private fun drawCheckout(canvas: Canvas, data: State, density: Float) {
        val r = rects[1]
        ink.textAlign = Paint.Align.CENTER
        ink.typeface = Typeface.DEFAULT_BOLD
        ink.color = Color.WHITE
        ink.textSize = 16f * resources.displayMetrics.scaledDensity
        canvas.drawText(data.checkout, r.centerX(), baseline(r.centerY() - if (data.subtitle.isEmpty()) 0f else 5f * density), ink)
        if (data.subtitle.isNotEmpty()) {
            ink.typeface = Typeface.DEFAULT
            ink.textSize = 9f * resources.displayMetrics.scaledDensity
            canvas.drawText(data.subtitle, r.centerX(), baseline(r.centerY() + 11f * density), ink)
        }
    }

    private fun kind(x: Float, y: Float): String? = when {
        rects[1].contains(x, y) -> "checkout"
        rects[0].contains(x, y) -> if (state?.showSelect == false) "price" else
            if (x < rects[0].left + rects[0].height()) "selectAll" else
            if (x < rects[0].left + rects[0].height() + resources.displayMetrics.widthPixels * .08f) "selectLabel" else "price"
        else -> null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!inputEnabled)return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downKind = kind(event.x, event.y) ?: return false
                downX = event.x; downY = event.y
                action(downKind!!, "down")
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) downKind = null
                return true
            }
            MotionEvent.ACTION_UP -> {
                downKind?.takeIf { it == kind(event.x, event.y) }?.let { action(it, "click") }
                downKind = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> { downKind = null; return true }
        }
        return downKind != null
    }

    fun release() {
        resetBackdrop()
        nodes.forEach { it.discardDisplayList() }
        foregroundNode.discardDisplayList(); foregroundKey = null; geometryWork.clear()
    }
    fun acceptCleanBackdrop(): Boolean {
        if (cleanBackdrop) return false
        cleanBackdrop = true
        return true
    }
    fun resetBackdrop() { cleanBackdrop = false }
    /** Background publication may notify from the optical worker. No View state
     * or RenderNode is mutated there; Android coalesces the next UI redraw. */
    fun onBackdropFramePublished() {
        if (cleanBackdrop) postInvalidate()
    }
}
