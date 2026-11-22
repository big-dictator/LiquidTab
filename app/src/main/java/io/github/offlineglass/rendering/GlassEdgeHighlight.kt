package io.github.offlineglass.rendering

import android.graphics.Canvas
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.RuntimeShader
import io.github.offlineglass.hook.GlassHostLayout
import java.util.WeakHashMap

/** The caller supplies the exact local material edge and configured light uniforms. */
internal object GlassEdgeHighlight {
    // Callers clip the centered stroke to the material, leaving 0.30 dp visible.
    const val BLACK_HAIRLINE_STROKE_DP = .60f
    const val LIGHT_BLACK_HAIRLINE_ALPHA = 70
    // Output opacity only: leave light distribution, feather width and the strength slider intact.
    private const val LIGHT_THEME_OPACITY = 200
    @Volatile var transparency = io.github.offlineglass.config.GlassConfig.DEFAULT_HIGHLIGHT_TRANSPARENCY_PERCENT / 100f
    private fun opacity(dark: Boolean, transparency: Float): Int =
        ((if (dark) 185 else LIGHT_THEME_OPACITY) * (1f - transparency) + .5f).toInt()
    fun fallbackLightAlpha(alpha: Int, dark: Boolean, transparency: Float = this.transparency): Int =
        ((if (dark) alpha.toFloat() else alpha * LIGHT_THEME_OPACITY / 255f) *
            (1f - transparency) + .5f).toInt()
    private val widths = floatArrayOf(4.5f, 3.3f, 2.3f, 1.3f)
    private class EdgeState {
        val mask = ContinuousEdgeFalloff()
        val bounds = RectF()
        val local = Path()
        var dark: Boolean? = null
        var tintAlpha = -1
        var measuredGeneration = -1
        var geometry = 0
    }
    private val continuousStates = WeakHashMap<RuntimeShader, EdgeState>()

    fun shader(source: String = GlassHostLayout.BLOOM_STROKE_DUAL_SHADER,
        customize: (String) -> String = { it }): RuntimeShader {
        val effect = runCatching { RuntimeShader(customize(ContinuousEdgeFalloff.source(source))) }
            .getOrElse { return RuntimeShader(customize(source)) }
        synchronized(continuousStates) { continuousStates[effect] = EdgeState() }
        return effect
    }

    /** One draw for the light. The caller still owns the black outline. */
    fun drawContinuousLight(canvas: Canvas, edge: Path, paint: Paint, shader: RuntimeShader,
        reach: Float, dark: Boolean, transparency: Float = this.transparency): Boolean {
        val state = synchronized(continuousStates) { continuousStates[shader] } ?: return false
        if (edge.isEmpty || reach <= 0f) return false
        val tintAlpha = opacity(dark, transparency)
        if (tintAlpha == 0) return true
        if (state.measuredGeneration != edge.generationId) {
            edge.computeBounds(state.bounds, true)
            if (state.bounds.isEmpty) return false
            edge.offset(-state.bounds.left, -state.bounds.top, state.local)
            state.geometry = state.local.approximate(.25f).contentHashCode()
            state.measuredGeneration = edge.generationId
        }
        state.mask.bind(shader, state.local, state.bounds.width(), state.bounds.height(), state.geometry, reach)
        if (state.dark != dark || state.tintAlpha != tintAlpha) {
            shader.setColorUniform("edgeTint", if (dark) Color.argb(tintAlpha, 210, 210, 210)
                else Color.argb(tintAlpha, 255, 255, 255))
            shader.setFloatUniform("whiteLight", if (dark) 0f else 1f)
            state.dark = dark
            state.tintAlpha = tintAlpha
        }
        val oldStyle = paint.style
        try {
            paint.style = Paint.Style.FILL
            canvas.drawPath(edge, paint)
        } finally { paint.style = oldStyle }
        return true
    }

    fun draw(canvas: Canvas, edge: Path, paint: Paint, shader: RuntimeShader, density: Float, dark: Boolean,
        lightEnabled: Boolean, transparency: Float = this.transparency, blackOutline: Boolean = true) {
        if (!canvas.isHardwareAccelerated || edge.isEmpty) return
        val save = canvas.save()
        val originalWidth = paint.strokeWidth
        val originalColor = paint.color
        val originalShader = paint.shader
        val originalBlend = paint.blendMode
        val originalStyle = paint.style
        try {
            canvas.clipPath(edge)
            paint.style = Paint.Style.STROKE
            if (lightEnabled) {
                paint.shader = shader
                if (!drawContinuousLight(canvas, edge, paint, shader, 4.25f * originalWidth, dark, transparency)) {
                    paint.alpha = fallbackLightAlpha(paint.alpha, dark, transparency)
                    shader.setFloatUniform("whiteLight", if (dark) 0f else 1f)
                    for (i in widths.indices) {
                        paint.strokeWidth = widths[i] * originalWidth
                        shader.setFloatUniform("featherBand", (4 - i).toFloat())
                        canvas.drawPath(edge, paint)
                    }
                }
            }
            if (blackOutline) {
                paint.shader = null
                paint.blendMode = BlendMode.SRC_OVER
                paint.color = Color.argb(if (dark) 78 else LIGHT_BLACK_HAIRLINE_ALPHA, 0, 0, 0)
                paint.strokeWidth = BLACK_HAIRLINE_STROKE_DP * density
                canvas.drawPath(edge, paint)
            }
        } finally {
            if (lightEnabled) shader.setFloatUniform("featherBand", 0f)
            paint.color = originalColor
            paint.shader = originalShader
            paint.blendMode = originalBlend
            paint.style = originalStyle
            paint.strokeWidth = originalWidth
            canvas.restoreToCount(save)
        }
    }
}

/** For independent surfaces that previously had a flat contour stroke. */
internal class NativeGlassEdge {
    private val effect by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { GlassEdgeHighlight.shader() }.getOrNull()?.apply {
            setColorUniform("strokeColor", Color.WHITE)
            setFloatUniform("strokeAlphaMul", .12f)
            setFloatUniform("lightDir1", -.7071068f, -.7071068f, -.05f)
            setFloatUniform("lightDir2", 0f, .19611613f, -.9805807f)
            for (suffix in listOf("1", "2")) setColorUniform("lightColor$suffix", Color.WHITE)
            setFloatUniform("lightIntensity1", 1f)
            setFloatUniform("lightIntensity2", .4f)
        }
    }
    private val bounds = RectF()
    private val origin = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, path: Path, density: Float, dark: Boolean, alpha: Float, width: Float = 1f) {
        if (!canvas.isHardwareAccelerated || path.isEmpty) return
        val shader = effect ?: return
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return
        origin.setTranslate(bounds.left, bounds.top)
        shader.setLocalMatrix(origin)
        shader.setFloatUniform("halfView", bounds.width() * .5f, bounds.height() * .5f)
        shader.setFloatUniform("highlightAlpha", alpha.coerceAtMost(1f))
        paint.strokeWidth = density * width
        GlassEdgeHighlight.draw(canvas, path, paint, shader, density, dark, alpha > 0f)
    }
}
