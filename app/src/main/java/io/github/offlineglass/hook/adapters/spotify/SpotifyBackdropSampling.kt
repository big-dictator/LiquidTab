package io.github.offlineglass.hook.adapters.spotify

import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import io.github.offlineglass.rendering.NativeBackdropBlur
import kotlin.math.sqrt

/** Keep the native blur strength, but low-pass text before its large-kernel resampling. */
internal object SpotifyBackdropSampling {
    private val effects = LruCache<Float, RenderEffect>(12)
    @Synchronized
    fun effect(radius: Float): RenderEffect {
        if (radius <= 8f) return NativeBackdropBlur.effect(radius)
        effects[radius]?.let { return it }
        val preRadius = minOf(3f, radius * 0.25f)
        val remaining = sqrt((radius * radius - preRadius * preRadius).coerceAtLeast(0f))
        val pre = RenderEffect.createBlurEffect(preRadius, preRadius, Shader.TileMode.CLAMP)
        // Gaussian variances add; the combined blur retains the configured radius.
        val effect = RenderEffect.createChainEffect(NativeBackdropBlur.effect(remaining), pre)
        effects.put(radius, effect)
        return effect
    }

    /** RenderNodes are in their parent's coordinates. Preserve fractional transforms. */
    fun transform(parent: View, target: View, out: Matrix, targetGlobal: Matrix): Boolean {
        out.reset()
        targetGlobal.reset()
        parent.transformMatrixToGlobal(out)
        target.transformMatrixToGlobal(targetGlobal)
        if (!targetGlobal.invert(targetGlobal)) return false
        out.setConcat(targetGlobal, out)
        return true
    }
}
