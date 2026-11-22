package io.github.offlineglass.hook.adapters.xhs

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.Surface
import android.view.View
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import io.github.offlineglass.rendering.PostureGlass
import kotlin.math.ceil
import kotlin.math.sqrt

/** Note-page material shared by the top actions and the bottom comment/action capsules. */
internal class XhsNoteCapsule(private val owner: View, name: String) {
    private val backdrop = RenderNode("LiquidTab-XhsNote-$name")
    private val shadow = RenderNode("LiquidTab-XhsNoteShadow-$name")
    private val lens = runCatching { PostureGlass.shader(KYANT_LENS_SHADER) }.getOrNull()
    private val bloom by lazy { runCatching { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() }.getOrNull() }
    private val clip = Path()
    private val lightingPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var shadowKey = ""
    private var effectKey = ""

    fun draw(canvas: Canvas, rect: RectF, config: GlassConfig, dark: Boolean, tint: Int, sceneBase: Int,
             shadowColor: Int = DEFAULT_SHADOW, highlightStrength: Float = 1f, reuseBackdrop: Boolean = false,
             recordBackdrop: (Canvas) -> Unit, drawPress: ((Canvas) -> Unit)? = null) {
        val density = owner.resources.displayMetrics.density
        clip.setBottomBarSquircle(rect, rect.height() / 2f, config.cornerSmoothing)
        if ((!dark || shadowColor != DEFAULT_SHADOW) && canvas.isHardwareAccelerated)
            drawShadow(canvas, rect, config, density, shadowColor)
        val save = canvas.save()
        canvas.clipPath(clip)
        if (!config.solidBarEnabled && canvas.isHardwareAccelerated)
            drawBackdrop(canvas, rect, config, density, sceneBase, reuseBackdrop, recordBackdrop)
        paint.style = Paint.Style.FILL
        paint.color = tint
        canvas.drawRect(rect, paint)
        drawPress?.invoke(canvas)
        canvas.restoreToCount(save)
        if (config.outlineEnabled) {
            if (config.liquidGlassEnabled && !config.solidBarEnabled) {
                drawHighlight(canvas, rect, config, density, highlightStrength, dark)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 0.6f
                paint.color = 0x0AFFFFFF
                paint.alpha = (10 * highlightStrength).toInt()
                canvas.drawPath(clip, paint)
            }
        }
    }

    private fun drawShadow(canvas: Canvas, rect: RectF, config: GlassConfig, density: Float, color: Int) {
        val pad = (30f * density).toInt()
        val width = ceil(rect.width()).toInt() + pad * 2
        val height = ceil(rect.height()).toInt() + pad * 2
        val bounds = RectF(pad.toFloat(), pad.toFloat(), pad + rect.width(), pad + rect.height())
        val key = "$width:$height:$density:${config.cornerSmoothing}:$color"
        if (shadowKey != key || !shadow.hasDisplayList()) {
            shadowKey = key
            shadow.setPosition(0, 0, width, height)
            shadow.setRenderEffect(RenderEffect.createBlurEffect(10f * density, 10f * density, Shader.TileMode.DECAL))
            val recording = shadow.beginRecording(width, height)
            try {
                val path = Path().apply { setBottomBarSquircle(bounds, rect.height() / 2f, config.cornerSmoothing) }
                recording.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            } finally { shadow.endRecording() }
        }
        val save = canvas.save()
        canvas.clipOutPath(clip)
        canvas.translate(rect.left - pad, rect.top - pad)
        PostureGlass.shadow(canvas, owner, bounds, 30f * density) { canvas.drawRenderNode(shadow) }
        canvas.restoreToCount(save)
    }

    private fun drawBackdrop(canvas: Canvas, rect: RectF, config: GlassConfig, density: Float, sceneBase: Int,
                             reuse: Boolean, record: (Canvas) -> Unit) {
        val padding = (24f * density * config.refractionPaddingScale).toInt()
        val width = rect.width().toInt() + padding * 2
        val height = rect.height().toInt() + padding * 2
        val dimensionsChanged = backdrop.setPosition(0, 0, width, height)
        lens?.let { PostureGlass.sync(it, owner, config.postureEffectsEnabled && config.liquidGlassEnabled,
            config.liquidViscosity, config.flowHorizontalStrength, config.flowVerticalStrength) }
        val key = "$width:$height:$density:${config.blurRadius}:${config.liquidGlassEnabled}:${config.refractionStrength}"
        val postureChanged = lens?.let(PostureGlass::effectChanged) == true
        if (effectKey != key || postureChanged) {
            effectKey = key
            // Near-clear sampling keeps letters legible as they enter the lens.
            val radius = minOf(config.blurRadius, 0.5f) * density
            val blur = if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null
            val refraction = if (config.liquidGlassEnabled) lens?.let {
                it.setFloatUniform("size", rect.width(), rect.height())
                it.setFloatUniform("offset", -padding.toFloat(), -padding.toFloat())
                val corner = rect.height() / 2f
                it.setFloatUniform("cornerRadii", corner, corner, corner, corner)
                it.setFloatUniform("refractionHeight", 12f * density)
                it.setFloatUniform("refractionAmount", -24f * density * config.refractionScale)
                it.setFloatUniform("depthEffect", 0f)
                RenderEffect.createRuntimeShaderEffect(it, "content")
            } else null
            backdrop.setRenderEffect(if (blur != null && refraction != null)
                RenderEffect.createChainEffect(refraction, blur) else refraction ?: blur)
        }
        if (!reuse || dimensionsChanged || !backdrop.hasDisplayList()) {
            val recording = backdrop.beginRecording(width, height)
            try {
                recording.drawColor(sceneBase)
                recording.translate(padding - rect.left, padding - rect.top)
                record(recording)
            } finally { backdrop.endRecording() }
        }
        val save = canvas.save()
        canvas.translate(rect.left - padding, rect.top - padding)
        canvas.drawRenderNode(backdrop)
        canvas.restoreToCount(save)
    }

    private fun drawHighlight(canvas: Canvas, rect: RectF, config: GlassConfig, density: Float, strength: Float, dark: Boolean) {
        if (!canvas.isHardwareAccelerated) return
        val shader = bloom ?: return
        var gx = if (config.postureEffectsEnabled) BloomTiltTracker.gravityX else 0f
        var gy = if (config.postureEffectsEnabled) BloomTiltTracker.gravityY else 0f
        when (owner.display?.rotation) {
            Surface.ROTATION_90 -> { val x = gx; gx = -gy; gy = x }
            Surface.ROTATION_180 -> { gx = -gx; gy = -gy }
            Surface.ROTATION_270 -> { val x = gx; gx = gy; gy = -x }
        }
        val length = sqrt(gx * gx + gy * gy)
        if (length > 0.1f) { gx /= length; gy /= length } else { gx = 0f; gy = -1f }
        val c = 0.70710677f
        shader.setFloatUniform("halfView", rect.width() / 2f, rect.height() / 2f)
        shader.setFloatUniform("highlightAlpha", 0.50f * strength * config.darkBarHighlightStrength)
        shader.setColorUniform("strokeColor", Color.WHITE)
        shader.setFloatUniform("strokeAlphaMul", 0.12f)
        shader.setFloatUniform("lightDir1", c * gx + c * gy, -c * gx + c * gy, -0.05f)
        shader.setColorUniform("lightColor1", Color.WHITE)
        shader.setFloatUniform("lightIntensity1", 1f)
        shader.setFloatUniform("lightDir2", 0f, 0.19611613f, -0.9805807f)
        shader.setColorUniform("lightColor2", Color.WHITE)
        shader.setFloatUniform("lightIntensity2", 0.4f)
        lightingPaint.shader = shader
        lightingPaint.blendMode = BlendMode.SCREEN
        lightingPaint.strokeWidth = density
        lightingPath.setBottomBarSquircle(RectF(0f, 0f, rect.width(), rect.height()), rect.height() / 2f, config.cornerSmoothing)
        val save = canvas.save()
        canvas.translate(rect.left, rect.top)
        io.github.offlineglass.rendering.GlassEdgeHighlight.draw(canvas, lightingPath, lightingPaint, shader, density, dark, config.darkBarHighlightStrength > 0f)
        canvas.restoreToCount(save)
        lightingPaint.shader = null
    }

    fun resetBackdrop() { backdrop.discardDisplayList() }
    fun release() { resetBackdrop(); shadow.discardDisplayList() }

    companion object {
        const val DEFAULT_SHADOW = 0x30000000
        private val renderNodeField by lazy {
            runCatching { View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true } }.getOrNull()
        }

        fun renderNode(view: View): RenderNode? = runCatching { renderNodeField?.get(view) as? RenderNode }.getOrNull()

    }
}
