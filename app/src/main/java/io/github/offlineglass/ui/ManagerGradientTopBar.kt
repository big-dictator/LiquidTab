package io.github.offlineglass.ui

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Native Gaussian pyramid. Adjacent radius levels form a partition of unity:
 * no sparse screen-space kernel, alpha normalization or translucent original
 * overlay. Only the final clear end uses the unblurred page.
 */
private const val LEVEL_MASK = """
uniform shader content;
uniform float veilHeight;
uniform float level;
half4 main(float2 coord) {
    float t = clamp(coord.y / max(veilHeight, 1.0), 0.0, 1.0);
    float position = smoothstep(0.0, 1.0, t) * 4.0;
    float weight = max(1.0 - abs(position - level), 0.0);
    return content.eval(coord) * half(weight);
}
"""

@Composable
internal fun rememberManagerGradientTopBlurEffect(
    enabled: Boolean,
    veilHeightPx: Float,
    density: Float,
): ComposeRenderEffect? = remember(enabled, veilHeightPx, density) {
    if (!enabled || veilHeightPx <= 0f) null
    else runCatching {
        // Descending genuine Gaussian radii. PLUS sums premultiplied colors
        // and alpha with weights totaling exactly 1, unlike SRC_OVER, which
        // would incorrectly mix unblurred details into each blurred level.
        val radii = floatArrayOf(32f, 18f, 8f, 2f, 0f)
        var accumulated: RenderEffect? = null
        radii.forEachIndexed { index, radius ->
            val mask = RuntimeShader(LEVEL_MASK)
            mask.setFloatUniform("veilHeight", veilHeightPx)
            mask.setFloatUniform("level", index.toFloat())
            val masking = RenderEffect.createRuntimeShaderEffect(mask, "content")
            val effect = if (radius == 0f) masking else {
                val px = radius * density
                RenderEffect.createChainEffect(
                    masking,
                    RenderEffect.createBlurEffect(px, px, Shader.TileMode.MIRROR),
                )
            }
            accumulated = accumulated?.let {
                RenderEffect.createBlendModeEffect(it, effect, BlendMode.PLUS)
            } ?: effect
        }
        accumulated?.asComposeRenderEffect()
    }.getOrNull()
}
