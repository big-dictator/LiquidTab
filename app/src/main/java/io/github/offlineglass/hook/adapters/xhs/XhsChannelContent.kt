package io.github.offlineglass.hook.adapters.xhs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.widget.FrameLayout

/** Applies alpha to channel content only. The glass behind this layer is never faded. */
internal class XhsChannelContent(context: Context) : FrameLayout(context) {
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    init { clipChildren = true; clipToPadding = true }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val fade = (28f * resources.displayMetrics.density).coerceAtMost(h.toFloat())
        mask.shader = LinearGradient(0f, (h - fade).coerceAtLeast(0f), 0f, h.toFloat().coerceAtLeast(1f),
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    override fun dispatchDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        canvas.restoreToCount(layer)
    }
}
