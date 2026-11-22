package io.github.offlineglass.hook.adapters.home_glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** A cached, translucent home-edge tint; no scene capture or blur work. */
internal open class HomeEdgeColorGradient(private val top: Boolean, private val topAlpha: Int = 242) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dark = false
    private var dirty = true

    fun setDark(value: Boolean) {
        if (dark == value) return
        dark = value; dirty = true; invalidateSelf()
    }

    override fun onBoundsChange(bounds: Rect) { dirty = true }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (dirty) {
            val rgb = if (dark) 0x0018181B else 0x00FFFFFF
            val positions = if (top) FloatArray(17) { it / 16f } else floatArrayOf(0f, .35f, .75f, 1f)
            val colors = if (top) IntArray(positions.size) { index ->
                val t = positions[index]
                // Smoothstep gives both edges zero slope; extra stops avoid
                // the visible kink of a short piecewise-linear fade.
                val alpha = (topAlpha * (1f - t*t*(3f-2f*t))).toInt().coerceIn(0, topAlpha)
                (alpha shl 24) or rgb
            } else intArrayOf(rgb, (72 shl 24) or rgb, (168 shl 24) or rgb, (199 shl 24) or rgb)
            paint.shader = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(), colors,
                positions, Shader.TileMode.CLAMP)
            dirty = false
        }
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Drawable opacity")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
