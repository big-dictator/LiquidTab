package io.github.offlineglass.hook.adapters.taobao

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import kotlin.math.max

/** Message count is refracted with its native slot but keeps its own orange fill. */
internal class TaobaoBadge {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5000.toInt()
        style = Paint.Style.FILL
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun draw(canvas: Canvas, host: GlassHostLayout, contentScale: Float) {
        val source = host.adapterNavigationSource?.takeIf { it.width > 0 && it.height > 0 } ?: return
        val transform = host.adapterTabTransform(2) ?: return
        val badge = findBadge(source) ?: return
        val scale = transform[0]
        val dx = transform[1]
        val dy = transform[2]
        val sourceScale = transform[3]
        val density = transform[4]
        val sourceLocation = IntArray(2).also(source::getLocationInWindow)
        val badgeLocation = IntArray(2).also(badge::getLocationInWindow)
        val inset = 1.5f * density / sourceScale
        val nativeRect = RectF(
            badgeLocation[0] - sourceLocation[0] - inset,
            badgeLocation[1] - sourceLocation[1] - inset,
            badgeLocation[0] - sourceLocation[0] + badge.width + inset,
            badgeLocation[1] - sourceLocation[1] + badge.height + inset,
        )
        val centerX = dx + nativeRect.centerX() * scale
        val centerY = dy + nativeRect.centerY() * scale
        val nativeWidth = (nativeRect.width() - 3f * density / scale).coerceAtLeast(1f)
        val nativeHeight = (nativeRect.height() - 3f * density / scale).coerceAtLeast(1f)
        val halfWidth = max(nativeWidth * scale / 2f, 8.5f * density)
        val halfHeight = max(nativeHeight * scale / 2f, 8.5f * density)
        val rect = RectF(centerX - halfWidth, centerY - halfHeight,
            centerX + halfWidth, centerY + halfHeight)
        val saved = canvas.save()
        canvas.scale(contentScale.coerceAtLeast(0.01f), contentScale.coerceAtLeast(0.01f),
            centerX, host.height / 2f)
        canvas.drawRoundRect(rect, halfHeight, halfHeight, fill)
        text.textSize = badge.textSize * scale
        val baseline = centerY - (text.ascent() + text.descent()) / 2f
        canvas.drawText(badge.text?.toString().orEmpty(), centerX, baseline, text)
        canvas.restoreToCount(saved)
    }

    private fun findBadge(source: ViewGroup): TextView? {
        val messageRoot = source.getChildAt(2) ?: return null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += messageRoot to 0
        var best: TextView? = null
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is TextView) {
                val value = view.text?.toString()?.trim().orEmpty()
                if (value.matches(Regex("\\d{1,3}\\+?")) && view.width > 0 && view.height > 0) {
                    if (best == null || view.width * view.height < best.width * best.height) best = view
                }
            }
            if (view is ViewGroup && depth < 6) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to depth + 1
            }
        }
        return best
    }
}
