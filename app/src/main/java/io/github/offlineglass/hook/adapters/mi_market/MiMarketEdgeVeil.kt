package io.github.offlineglass.hook.adapters.mi_market

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Same color/stops as Bilibili's translucent edge tint, without extra sampling. */
internal class MiMarketEdgeVeil(context: Context) : View(context), AdapterOwnedOverlay {
    private val paint = Paint()
    private var key = emptyList<Int>()
    private var top: Shader? = null
    private var bottom: Shader? = null
    private var topEnd = 0
    private var bottomStart = 0
    init { isClickable=false; isFocusable=false; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun update(dark: Boolean, topHeight: Int, bottomTop: Int) {
        val next = listOf(width,height,if(dark)1 else 0,topHeight,bottomTop)
        if (key == next) return
        key=next; topEnd=topHeight; bottomStart=bottomTop
        val rgb = if(dark)0x0018181B else 0x00FFFFFF
        val positions=FloatArray(17){it/16f}
        top=LinearGradient(0f,0f,0f,topHeight.coerceAtLeast(1).toFloat(),IntArray(17){
            val t=positions[it]; ((242f*(1f-t*t*(3f-2f*t))).toInt() shl 24) or rgb
        },positions,Shader.TileMode.CLAMP)
        bottom=LinearGradient(0f,bottomTop.toFloat(),0f,height.coerceAtLeast(bottomTop+1).toFloat(),
            intArrayOf(rgb,(72 shl 24)or rgb,(168 shl 24)or rgb,(199 shl 24)or rgb),
            floatArrayOf(0f,.35f,.75f,1f),Shader.TileMode.CLAMP)
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        paint.shader=top; canvas.drawRect(0f,0f,width.toFloat(),topEnd.toFloat(),paint)
        paint.shader=bottom; canvas.drawRect(0f,bottomStart.toFloat(),width.toFloat(),height.toFloat(),paint)
    }
}
