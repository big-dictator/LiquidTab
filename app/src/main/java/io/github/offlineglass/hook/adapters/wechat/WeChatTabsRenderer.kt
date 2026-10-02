package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView

internal class WeChatTabsRenderer {
    private data class WeChatTabViews(
        val icon: View?,
        val label: TextView?,
        val badgeDot: ImageView?,
        val badgeCount: TextView?,
        val tab: ViewGroup,
    )

    private var weChatCachedTabs: List<WeChatTabViews>? = null
    private var weChatTabsCacheSource: View? = null

    fun draw(canvas: Canvas, source: ViewGroup, density: Float) {

        val inner = (0 until source.childCount)

            .map { source.getChildAt(it) }

            .filterIsInstance<ViewGroup>()

            .firstOrNull { it.childCount >= 3 }

            ?: return

        val tabCount = inner.childCount

        if (tabCount <= 0) return



        // Use cached tab references or rebuild cache

        val tabs = weChatCachedTabs?.takeIf {

            weChatTabsCacheSource === source && it.size == tabCount &&

                it.all { tc -> tc.tab.isAttachedToWindow }

        } ?: buildList {

            for (i in 0 until tabCount) {

                val tab = inner.getChildAt(i) as? ViewGroup ?: continue

                var iconView: View? = null

                var labelView: TextView? = null

                var badgeDot: ImageView? = null

                var badgeCount: TextView? = null

                val stack = ArrayDeque<View>()

                stack += tab

                while (stack.isNotEmpty()) {

                    val v = stack.removeLast()

                    val name = v.javaClass.simpleName

                    when {

                        name == "TabIconView" -> iconView = v

                        name == "ImageView" -> if (badgeDot == null) badgeDot = v as? ImageView

                        v is TextView -> {

                            if (labelView == null) labelView = v

                            else if (badgeCount == null) badgeCount = v as? TextView

                        }

                    }

                    if (v is ViewGroup) {

                        for (j in 0 until v.childCount) stack += v.getChildAt(j)

                    }

                }

                add(WeChatTabViews(iconView, labelView, badgeDot, badgeCount, tab))

            }

        }.also {

            weChatCachedTabs = it

            weChatTabsCacheSource = source

        }



        // slotTransform already centers source content within the pill.

        // We draw in source coordinate space. The pill height in source

        // coordinates is height / slotTransform.scale. Use that to

        // vertically center icon + spacing + text within the pill.

        val firstIcon = tabs.firstOrNull { it.icon != null }?.icon

        val iconSize = firstIcon?.width?.toFloat() ?: (40f * density)

        val labelH = tabs.firstOrNull { it.label != null }?.label?.let {

            val tp = android.text.TextPaint(it.paint)

            tp.descent() - tp.ascent()

        } ?: (12f * density)

        val gap = 3f * density

        val totalH = iconSize + gap + labelH

        // pillHeight in source coords = height / slotTransform.scale

        val pillH = source.height.toFloat()

        val baseY = (pillH - totalH) * 0.5f

        val iconCenterY = baseY + iconSize * 0.5f

        val textCenterY = baseY + iconSize + gap + labelH * 0.5f



        val tabWidth = source.width.toFloat() / tabCount



        for ((idx, tc) in tabs.withIndex()) {

            // Center each tab horizontally within its slot

            val centerX = (idx + 0.5f) * tabWidth



            // Draw icon centered at (centerX, iconCenterY)

            val icon = tc.icon

            if (icon != null && icon.width > 0 && icon.height > 0) {

                val iW = icon.width.toFloat()

                val iH = icon.height.toFloat()

                val tx = centerX - iW * 0.5f

                val ty = iconCenterY - iH * 0.5f

                canvas.save()

                canvas.translate(tx, ty)

                // Enlarge icons to 1.15x around their own centre; text and

                // notification badge keep their original size and position.

                canvas.scale(1.15f, 1.15f, iW * 0.5f, iH * 0.5f)

                val savedAlpha = icon.alpha

                icon.alpha = 1f

                icon.visibility = View.VISIBLE

                icon.draw(canvas)

                icon.alpha = savedAlpha

                canvas.restore()



                // Draw label text centered below icon

                val label = tc.label

                if (label != null) {

                    val text = label.text

                    if (text != null && text.isNotEmpty()) {

                        val tp = android.text.TextPaint(label.paint)

                        tp.color = label.currentTextColor

                        tp.alpha = 255

                        val s = text.toString()

                        val tw = tp.measureText(s)

                        val lx = centerX - tw * 0.5f

                        val ly = textCenterY + (tp.descent() - tp.ascent()) * 0.5f - tp.descent()

                        canvas.drawText(s, lx, ly, tp)

                    }

                }



                // Draw notification badge if unread messages exist

                val cnt = tc.badgeCount

                if (cnt != null && cnt.visibility == View.VISIBLE) {

                    val text = cnt.text

                    if (text != null && text.isNotEmpty()) {

                        val br = 9f * density

                        val bx = centerX + iW * 0.5f - 4f * density

                        // Badge top edge slightly below pill top edge

                        val by = iconCenterY - iH * 0.1875f

                        val bp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

                        bp.color = android.graphics.Color.RED

                        canvas.drawCircle(bx, by, br, bp)

                        val cp = android.text.TextPaint(android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))

                        cp.color = android.graphics.Color.WHITE

                        cp.textSize = cnt.textSize.toFloat()

                        cp.isFakeBoldText = true

                        val cs = text.toString()

                        val cw = cp.measureText(cs)

                        val cx = bx - cw * 0.5f

                        val cy = by + (cp.descent() - cp.ascent()) * 0.5f - cp.descent()

                        canvas.drawText(cs, cx, cy, cp)

                    }

                }

            }

        }

    }
}

