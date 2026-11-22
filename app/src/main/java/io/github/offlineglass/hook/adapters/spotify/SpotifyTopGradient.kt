package io.github.offlineglass.hook.adapters.spotify

import android.graphics.*
import android.graphics.drawable.Drawable

/** Bilibili's approved dark smoothstep veil, owned by Spotify's overlay. */
internal class SpotifyTopGradient: Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val positions=FloatArray(17) { it/16f }
    private val colors=IntArray(17) { index ->
        val t=positions[index]
        val alpha=(242f*(1f-t*t*(3f-2f*t))).toInt().coerceIn(0,242)
        (alpha shl 24) or 0x0018181B
    }
    private var fadeHeight=0
    private var dirty=true
    fun setFadeHeight(height: Int) {
        val value=height.coerceAtLeast(0)
        if(value==fadeHeight)return
        fadeHeight=value;dirty=true;invalidateSelf()
    }
    override fun onBoundsChange(bounds: Rect) { dirty=true }
    override fun draw(canvas: Canvas) {
        if(bounds.isEmpty || fadeHeight<=0)return
        val bottom=(bounds.top+fadeHeight).coerceAtMost(bounds.bottom)
        if(dirty) {
            paint.shader=LinearGradient(0f,bounds.top.toFloat(),0f,bottom.toFloat(),colors,positions,Shader.TileMode.CLAMP)
            dirty=false
        }
        canvas.drawRect(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bottom.toFloat(),paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha;invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Drawable opacity")
    override fun getOpacity()=PixelFormat.TRANSLUCENT
}
