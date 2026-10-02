package io.github.offlineglass.rendering

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.ceil
import kotlin.math.max

/** Shared GPU material used by manager bars, injected bars and floating players.
 * Capturing/locating the input remains the caller's responsibility. No source
 * coordinate quantization, CPU blur, timers or temporal blending is performed.
 */
internal object NativeBackdropBlur {
    private data class Key(val radius: Float, val saturation: Float)
    private val effects = LruCache<Key, RenderEffect>(24)

    @Synchronized
    fun effect(radiusPx: Float, saturation: Float = 1.5f): RenderEffect {
        val radius = if (radiusPx.isFinite()) radiusPx.coerceAtLeast(0f) else 0f
        val key = Key(radius, saturation)
        effects.get(key)?.let { return it }
        val color = RenderEffect.createColorFilterEffect(
            ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }),
        )
        val result = if (radius > 0f) RenderEffect.createBlurEffect(
            radius, radius, color, Shader.TileMode.CLAMP,
        ) else color
        effects.put(key, result)
        return result
    }

    fun padding(radiusPx: Float, minimumPx: Float): Float =
        max(minimumPx, ceil(radiusPx.coerceAtLeast(0f) * 3f))
}
