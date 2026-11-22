package io.github.offlineglass.rendering

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import kotlin.math.ceil

/** Geometry-only distance mask. Never records app content or animates its bitmap. */
internal class ContinuousEdgeFalloff {
    private data class Key(val w: Float, val h: Float, val geometry: Int, val reach: Float)
    private var maskKey: Key? = null
    // Main panel and detached action may alternate on the same shader.
    private val masks = android.util.LruCache<Key, BitmapShader>(4)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        blendMode = BlendMode.SRC
    }

    fun bind(shader: RuntimeShader, path: Path, w: Float, h: Float, geometry: Int, reach: Float) {
        val key = Key(w, h, geometry, reach)
        if (key == maskKey) return
        val previous = masks.get(key)
        if (previous != null) {
            shader.setInputShader("edgeDistanceMask", previous)
            maskKey = key
            return
        }
        val bitmap = Bitmap.createBitmap(ceil(w).toInt().coerceAtLeast(1),
            ceil(h).toInt().coerceAtLeast(1), Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val bands = ceil(reach * 2f).toInt().coerceIn(8, 96)
        for (index in bands downTo 1) {
            ink.strokeWidth = 2f * reach * index / bands
            ink.color = Color.argb(255 - (255 * (index - 1) / bands), 255, 255, 255)
            canvas.drawPath(path, ink)
        }
        val mask = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
        shader.setInputShader("edgeDistanceMask", mask)
        masks.put(key, mask)
        maskKey = key
    }

    companion object {
        fun source(bloom: String): String =
            "uniform shader edgeDistanceMask;\nlayout(color) uniform half4 edgeTint;\n" + bloom.replace(
                "float2 n = delta / max(length(delta), 0.001);",
                """
                // Measure direction in capsule-relative coordinates instead
                // of pixels: otherwise most of the turn on a wide bar happens
                // within one bar-height of the midpoint.
                float2 lightDelta = delta / max(halfView, float2(1.0));
                lightDelta.x *= 1.35;
                float2 n = lightDelta / max(length(lightDelta), 0.001);
                """.trimIndent(),
            ).replace(
                "half a = half(highlightAlpha * gain);",
                """
                float brightness = clamp(lightEnergy * highlightAlpha / 0.75, 0.0, 1.0);
                float normalizedDistance = 1.0 - float(edgeDistanceMask.eval(fragCoord).a);
                // A gentle concave response across the full intensity range
                // avoids the steep middle slope of a narrow smoothstep.
                float widthResponse = brightness * (1.18 - 0.18 * brightness);
                float width = mix(0.11, 1.0, widthResponse);
                // Shift the crest slightly inside the glass. The outer shoulder
                // ends at the real clip edge; only the inner shoulder widens
                // with light intensity.
                float crest = mix(0.045, 0.085, brightness);
                float outerShoulder = mix(0.30, 1.0, smoothstep(0.0, crest, normalizedDistance));
                float innerShoulder = 1.0 - smoothstep(crest, min(1.0, crest + width), normalizedDistance);
                float falloff = outerShoulder * innerShoulder;
                half a = half(highlightAlpha * gain * falloff) * edgeTint.a;
                """.trimIndent(),
            ).replace("half4(rgb * a, a);", "half4(rgb * edgeTint.rgb * a, a);")
    }
}
