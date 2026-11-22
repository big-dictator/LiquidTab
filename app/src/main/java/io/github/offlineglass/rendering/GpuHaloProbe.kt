package io.github.offlineglass.rendering

import android.graphics.*
import android.os.Bundle
import kotlin.math.abs

/** Synthetic, offscreen only. Never selected by an app renderer. */
internal object GpuHaloProbe {
    fun run(): Bundle = runCatching {
        val shader = RuntimeShader("""
            uniform float4 box;
            uniform float radius;
            uniform float sigma;
            uniform float4 tint;
            half4 main(float2 p) {
                float2 q = abs(p - (box.xy + box.zw) * 0.5) -
                    ((box.zw - box.xy) * 0.5 - radius);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
                float t = max(d, 0.0) / sigma;
                float k = 1.0 / (1.0 + 0.2316419 * t);
                float tail = exp(-0.5*t*t) * k *
                    (0.1274148 + k*(-0.1422484 + k*(0.7107069 + k*(-0.7265760 + k*0.5307027))));
                float a = tint.a * tail * clamp(d + 0.5, 0.0, 1.0);
                return half4(tint.rgb * a, a);
            }
        """)
        val original = IndicatorInteractionLight()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        var cases = 0
        var failed = 0
        var changed = 0
        var maxDelta = 0
        for (dark in listOf(false, true)) for (frame in 0..8) {
            val t = frame / 8f
            val pulse = 1f - abs(t * 2f - 1f)
            val left = 39f + t * 76.375f
            val bounds = RectF(left, 65f - pulse * 3f, left + 136f + pulse * 12f, 121f + pulse * 3f)
            val radius = bounds.height() / 2f
            val density = 2f
            val scale = 1f + pulse * 0.16f
            val color = if (dark) 1f else 0f
            val alpha = (255f * (if (dark) 0.18f else 0.12f) * pulse).toInt() / 255f
            shader.setFloatUniform("box", bounds.left, bounds.top, bounds.right, bounds.bottom)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("sigma", 12f * density * 0.57735f + 0.5f)
            shader.setFloatUniform("tint", color, color, color, alpha)
            fun render(gpu: Boolean): IntArray {
                val bitmap = BoundedContourGpuProbe.renderNode(360, 190) { c ->
                    c.drawColor(if (dark) 0xff252a34.toInt() else 0xffe4edf7.toInt())
                    if (!dark) c.drawRect(180f, 0f, 360f, 190f, Paint().apply { this.color = 0xffa9c5dc.toInt() })
                    c.scale(scale, 1f + pulse * 0.11f, bounds.centerX(), bounds.centerY())
                    if (gpu) c.drawRect(bounds.left - 64f, bounds.top - 64f,
                        bounds.right + 64f, bounds.bottom + 64f, paint)
                    else original.outer(c, bounds, radius, density, dark, pulse)
                }
                try { return IntArray(360 * 190).also { bitmap.getPixels(it, 0, 360, 0, 0, 360, 190) } }
                finally { bitmap.recycle() }
            }
            val a = render(false)
            val b = render(true)
            val count = a.indices.count { a[it] != b[it] }
            changed += count
            if (count != 0) failed++
            for (i in a.indices) for (shift in 0..24 step 8) {
                maxDelta = maxOf(maxDelta, abs((a[i] ushr shift and 255) - (b[i] ushr shift and 255)))
            }
            cases++
        }
        Bundle().apply {
            putInt("frames", cases)
            putInt("differentFrames", failed)
            putInt("differentPixels", changed)
            putInt("maxChannelDelta", maxDelta)
        }
    }.getOrElse { error -> Bundle().apply { putString("error", error.stackTraceToString()) } }
}
