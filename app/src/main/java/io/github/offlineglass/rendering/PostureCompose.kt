package io.github.offlineglass.rendering

import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.shadow.Shadow
import io.github.offlineglass.hook.BloomTiltTracker
import java.lang.ref.WeakReference

internal object PostureCompose {
    data class Pose(val x: Float, val y: Float, val z: Float,
        val gravityX: Float, val gravityY: Float,
        val rippleAmplitude: Float = 0f, val rippleAge: Float = 0f,
        val rippleX: Float = 1f, val rippleY: Float = 0f,
        val reboundX: Float = 0f, val reboundY: Float = 0f)
    var viscosity = 25f
    var view = WeakReference<View>(null)
    var current by mutableStateOf(Pose(0f,0f,0f,0f,-1f))
        private set
    private var frameScheduled = false
    fun update() {
        val root = view.get() ?: return
        if (!PostureGlass.isTracking(root)) return
        val motion = LiquidMotion.sample(root, viscosity)
        val p = motion.pose
        current = Pose(p[0],p[1],p[2],BloomTiltTracker.gravityX,BloomTiltTracker.gravityY,
            motion.rippleAmplitude, if (motion.rippleAmplitude > 0f) motion.rippleAge else 0f,
            motion.rippleX, motion.rippleY, motion.reboundX, motion.reboundY)
        if (motion.active && !frameScheduled) {
            frameScheduled = true
            root.postOnAnimation { frameScheduled = false; update() }
        }
    }
    fun reset() {
        view.get()?.let(LiquidMotion::clear)
        current = Pose(0f,0f,0f,0f,-1f)
    }
}

/** Only the shadow is masked; glass, text, hit targets and blur stay untouched. */
internal fun Modifier.postureDropShadow(shape: Shape, shadow: Shadow, uniform: Boolean = false,
    topAlpha: Float = 1f, opacityScale: () -> Float = { 1f }): Modifier =
    drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size,layoutDirection,this@drawWithCache)) }.asAndroidPath()
        val rect = android.graphics.RectF(0f,0f,size.width,size.height)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.TRANSPARENT
            setShadowLayer(shadow.radius.toPx(),0f,0f,
                shadow.color.copy(alpha=(shadow.color.alpha*shadow.alpha).coerceIn(0f,1f)).toArgb())
        }
        var lastStrength = 1f
        val padding = shadow.radius.toPx() * 3.5f
        val topMask = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            blendMode = android.graphics.BlendMode.DST_IN
            shader = android.graphics.LinearGradient(0f, -padding, 0f, rect.centerY(),
                intArrayOf(android.graphics.Color.argb((topAlpha.coerceIn(0f, 1f) * 255).toInt(), 255, 255, 255),
                    android.graphics.Color.WHITE), null, android.graphics.Shader.TileMode.CLAMP)
        }
        onDrawBehind {
            // A uniform shadow is independent of pose. Subscribing it to the
            // liquid spring forces every idle toggle/slider shadow to redraw.
            if (!uniform) PostureCompose.current
            val canvas = drawContext.canvas.nativeCanvas
            val root = PostureCompose.view.get()
            val strength = (if ((uniform || (root != null && PostureGlass.isTracking(root))) &&
                shadow.color.red + shadow.color.green + shadow.color.blue < 1.5f) PostureGlass.SHADOW_STRENGTH else 1f) *
                opacityScale().coerceIn(0f, 2f)
            if (strength != lastStrength) {
                paint.setShadowLayer(shadow.radius.toPx(),0f,0f,shadow.color.copy(
                    alpha=(shadow.color.alpha*shadow.alpha*strength).coerceIn(0f,1f)).toArgb())
                lastStrength = strength
            }
            val draw = {
                val layer = if (topAlpha < 1f) canvas.saveLayer(-padding, -padding,
                    rect.right + padding, rect.bottom + padding, null) else -1
                val save = canvas.save()
                canvas.clipOutPath(path)
                canvas.drawPath(path,paint)
                canvas.restoreToCount(save)
                if (layer >= 0) {
                    canvas.drawRect(-padding, -padding, rect.right + padding, rect.bottom + padding, topMask)
                    canvas.restoreToCount(layer)
                }
            }
            if (!uniform && root != null && PostureGlass.isTracking(root))
                PostureGlass.shadow(canvas,root,rect,shadow.radius.toPx()*3.5f,draw)
            else draw()
        }
    }
