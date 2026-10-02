package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.roundToInt

internal class WeChatContactsVeilDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private var solidStartY = Float.NaN

    private var fadeHeight = Float.NaN

    private var surfaceColor = Color.TRANSPARENT

    private var drawableAlpha = 255



    fun update(solidStartY: Float, fadeHeight: Float, surfaceColor: Int) {

        if (abs(this.solidStartY - solidStartY) <= 0.5f &&

            abs(this.fadeHeight - fadeHeight) <= 0.5f && this.surfaceColor == surfaceColor

        ) return

        this.solidStartY = solidStartY

        this.fadeHeight = fadeHeight

        this.surfaceColor = surfaceColor

        invalidateSelf()

    }



    override fun draw(canvas: Canvas) {

        if (bounds.isEmpty || solidStartY.isNaN() || fadeHeight.isNaN()) return

        val red = Color.red(surfaceColor)

        val green = Color.green(surfaceColor)

        val blue = Color.blue(surfaceColor)

        val alphaScale = drawableAlpha / 255f

        val scaledAlpha = { value: Int -> (value * alphaScale).roundToInt().coerceIn(0, 255) }

        val fadeTop = (solidStartY - fadeHeight).coerceAtLeast(bounds.top.toFloat())

        val edge = solidStartY.coerceIn(fadeTop + 1f, bounds.bottom.toFloat())

        paint.shader = LinearGradient(

            0f,

            fadeTop,

            0f,

            edge,

            intArrayOf(

                Color.argb(scaledAlpha(0), red, green, blue),

                Color.argb(scaledAlpha(3), red, green, blue),

                Color.argb(scaledAlpha(12), red, green, blue),

                Color.argb(scaledAlpha(32), red, green, blue),

                Color.argb(scaledAlpha(62), red, green, blue),

                Color.argb(scaledAlpha(103), red, green, blue),

                Color.argb(scaledAlpha(148), red, green, blue),

                Color.argb(scaledAlpha(188), red, green, blue),

                Color.argb(scaledAlpha(220), red, green, blue),

                Color.argb(scaledAlpha(242), red, green, blue),

                Color.argb(scaledAlpha(255), red, green, blue),

            ),

            floatArrayOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f),

            Shader.TileMode.CLAMP,

        )

        canvas.drawRect(bounds, paint)

        paint.shader = null

    }



    override fun setAlpha(alpha: Int) {

        val next = alpha.coerceIn(0, 255)

        if (drawableAlpha == next) return

        drawableAlpha = next

        invalidateSelf()

    }



    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {

        paint.colorFilter = colorFilter

        invalidateSelf()

    }



    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

}
