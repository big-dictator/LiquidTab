package io.github.offlineglass.ui

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.rendering.GlassEdgeHighlight

internal val managerHighlightTransparency = androidx.compose.runtime.mutableFloatStateOf(0f)

/** Compose's glass surface and edge are recorded under the same transform. */
internal class ManagerGlassEdge {
    private val shader by lazy(LazyThreadSafetyMode.NONE) {
        runCatching { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() }.getOrNull()?.apply {
            setColorUniform("strokeColor", Color.WHITE)
            setFloatUniform("strokeAlphaMul", .12f)
            setColorUniform("lightColor1", Color.WHITE)
            setColorUniform("lightColor2", Color.WHITE)
            setFloatUniform("lightIntensity1", 1f)
            setFloatUniform("lightIntensity2", .4f)
            setFloatUniform("lightDir2", 0f, .19611613f, -.9805807f)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var cachedOutline: Outline? = null
    private val fallbackPath = Path()
    private val radii = FloatArray(8)

    fun draw(scope: DrawScope, shape: Shape, dark: Boolean, alpha: Float,
        lightX: Float = .5f, lightY: Float = -.3f, blackOutline: Boolean = true) {
        if (scope.size.width <= 0f || scope.size.height <= 0f) return
        val target = scope.drawContext.canvas.nativeCanvas
        if (!target.isHardwareAccelerated) return
        val effect = shader ?: return
        val outline = shape.createOutline(scope.size, scope.layoutDirection, scope)
        val path = if (outline is Outline.Generic) outline.path.asAndroidPath() else {
            if (outline != cachedOutline) {
                fallbackPath.rewind()
                when (outline) {
                    is Outline.Rectangle -> outline.rect.run {
                        fallbackPath.addRect(left, top, right, bottom, Path.Direction.CW)
                    }
                    is Outline.Rounded -> outline.roundRect.run {
                        radii[0] = topLeftCornerRadius.x; radii[1] = topLeftCornerRadius.y
                        radii[2] = topRightCornerRadius.x; radii[3] = topRightCornerRadius.y
                        radii[4] = bottomRightCornerRadius.x; radii[5] = bottomRightCornerRadius.y
                        radii[6] = bottomLeftCornerRadius.x; radii[7] = bottomLeftCornerRadius.y
                        fallbackPath.addRoundRect(left, top, right, bottom, radii, Path.Direction.CW)
                    }
                    else -> Unit
                }
                cachedOutline = outline
            }
            fallbackPath
        }
        effect.setFloatUniform("halfView", scope.size.width * .5f, scope.size.height * .5f)
        effect.setFloatUniform("highlightAlpha", alpha.coerceAtMost(1f))
        effect.setFloatUniform("lightDir1", lightX, lightY, -.05f)
        paint.strokeWidth = scope.density
        GlassEdgeHighlight.draw(target, path, paint, effect, scope.density, dark, alpha > 0f,
            managerHighlightTransparency.floatValue, blackOutline = blackOutline)
    }
}
