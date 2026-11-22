package io.github.offlineglass.rendering

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.os.Bundle
import io.github.offlineglass.hook.GlassHostLayout

/** Debug-only synthetic surfaces. Never reads an application's window or backdrop. */
internal object GlassEdgeColorProbe {
    fun run(): Bundle = runCatching {
        val base = GlassHostLayout.BLOOM_STROKE_DUAL_SHADER
        val edge = Path().apply { addRoundRect(0f, 0f, 96f, 48f, 24f, 24f, Path.Direction.CW) }
        val continuous = GlassEdgeHighlight.shader()
        val fallback = RuntimeShader(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (shader in listOf(continuous, fallback)) configure(shader)
        var samples = 0
        for (isContinuous in listOf(true, false)) {
            fun render(background: Int, transparency: Float): IntArray {
                val bitmap = BoundedContourGpuProbe.renderNode(120, 72) { canvas ->
                    canvas.drawColor(background)
                    canvas.translate(12f, 12f)
                    val shader = if (isContinuous) continuous else fallback
                    paint.shader = shader
                    paint.alpha = 255
                    if (isContinuous) {
                        check(GlassEdgeHighlight.drawContinuousLight(canvas, edge, paint, shader,
                            reach = 6f, dark = false, transparency = transparency))
                    } else {
                        shader.setFloatUniform("whiteLight", 1f)
                        shader.setFloatUniform("featherBand", 1f)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 3f
                        paint.alpha = GlassEdgeHighlight.fallbackLightAlpha(255, false, transparency)
                        canvas.drawPath(edge, paint)
                    }
                }
                return try {
                    IntArray(120 * 72).also { bitmap.getPixels(it, 0, 120, 0, 0, 120, 72) }
                } finally { bitmap.recycle() }
            }
            for (background in listOf(Color.WHITE, 0xffd6e2ed.toInt(), 0xff8293a3.toInt())) {
                val full = render(background, 0f)
                val faded = render(background, .5f)
                val disabled = render(background, 1f)
                check(disabled.all { it == background }) { "100% transparency must emit no light" }
                var addedLight = false
                for (i in full.indices) for (shift in intArrayOf(0, 8, 16)) {
                    val bg = background ushr shift and 255
                    val bright = full[i] ushr shift and 255
                    val dim = faded[i] ushr shift and 255
                    check(bright >= dim && dim >= bg) { "Light mode must not darken the underlying color" }
                    if (bright > bg) addedLight = true
                    samples++
                }
                if (background != Color.WHITE) check(addedLight) { "Non-white backgrounds should show the highlight" }
            }
        }
        Bundle().apply { putBoolean("passed", true); putInt("channelSamples", samples) }
    }.getOrElse { error -> Bundle().apply { putBoolean("passed", false); putString("error", error.stackTraceToString()) } }

    private fun configure(shader: RuntimeShader) {
        shader.setFloatUniform("halfView", 48f, 24f)
        shader.setFloatUniform("highlightAlpha", .8f)
        shader.setColorUniform("strokeColor", Color.WHITE)
        shader.setFloatUniform("strokeAlphaMul", .12f)
        shader.setFloatUniform("lightDir1", 1f, 0f, 0f)
        shader.setFloatUniform("lightDir2", 0f, 1f, 0f)
        shader.setColorUniform("lightColor1", Color.WHITE)
        shader.setColorUniform("lightColor2", Color.WHITE)
        shader.setFloatUniform("lightIntensity1", 1f)
        shader.setFloatUniform("lightIntensity2", .4f)
        shader.setFloatUniform("featherBand", 0f)
    }
}
