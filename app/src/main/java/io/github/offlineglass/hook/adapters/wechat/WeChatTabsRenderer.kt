package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.NavigationLabelLocalization

internal class WeChatTabsRenderer {
    private val focusFields = HashMap<Class<*>, java.lang.reflect.Field?>()
    private data class WeChatTabViews(
        val icon: View?,
        val label: TextView?,
        val badgeDot: ImageView?,
        val badgeCount: TextView?,
        val tab: ViewGroup,
    )

    private var weChatCachedTabs: List<WeChatTabViews>? = null
    private var weChatTabsCacheSource: View? = null
    private var foregroundResources: android.content.res.Resources? = null
    private var foregroundColorId = 0
    private val textBounds = android.graphics.Rect()
    private val labelPaint = android.text.TextPaint()
    private val badgePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.RED
    }
    private val badgeTextPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        isFakeBoldText = true
    }

    private fun belongsTo(view: View?, parent: ViewGroup): Boolean {
        var current = view ?: return true
        repeat(16) {
            if (current === parent) return true
            current = current.parent as? View ?: return false
        }
        return false
    }

    fun draw(canvas: Canvas, source: ViewGroup, density: Float, configIconScale: Float = 1f,
             selectedArtwork: Boolean? = null, dark: Boolean = false, managerEnglishLabels: Boolean = false,
             iconOnly: Boolean = false, hostHeight: Float? = null, hostWidth: Int = source.width) {

        var remaining = 256
        fun findTabs(root: ViewGroup, depth: Int = 0): List<ViewGroup>? {
            if (depth >= 16 || remaining-- <= 0) return null
            if (root.childCount == 4) {
                val candidates = (0 until root.childCount).mapNotNull { root.getChildAt(it) as? ViewGroup }
                if (candidates.size == 4 && candidates.all { tab ->
                        val stack = ArrayDeque<View>()
                        stack += tab
                        var hasIcon = false
                        var hasLabel = false
                        while (stack.isNotEmpty() && remaining-- > 0) {
                            val view = stack.removeLast()
                            if (view.javaClass.simpleName == "TabIconView") hasIcon = true
                            if (view is TextView) hasLabel = true
                            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
                        }
                        hasIcon && hasLabel
                    }) return candidates
            }
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i) as? ViewGroup ?: continue
                findTabs(child, depth + 1)?.let { return it }
            }
            return null
        }
        val tabs = weChatCachedTabs?.takeIf { cached ->
            val parent = cached.firstOrNull()?.tab?.parent as? ViewGroup
            weChatTabsCacheSource === source && parent != null && parent.childCount == 4 &&
                belongsTo(parent, source) && cached.indices.all { index ->
                    val tab = cached[index]
                    parent.getChildAt(index) === tab.tab && tab.tab.isAttachedToWindow &&
                        belongsTo(tab.icon, tab.tab) && belongsTo(tab.label, tab.tab) &&
                        belongsTo(tab.badgeDot, tab.tab) && belongsTo(tab.badgeCount, tab.tab)
                }
        } ?: run {
            val tabViews = findTabs(source) ?: return
            buildList {
                for (tab in tabViews) {

                    var iconView: View? = null

                    var labelView: TextView? = null

                    var badgeDot: ImageView? = null

                    var badgeCount: TextView? = null

                    val stack = ArrayDeque<View>()

                    stack += tab

                    while (stack.isNotEmpty() && remaining-- > 0) {

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

                    if (stack.isNotEmpty() || iconView == null || labelView == null) return
                    add(WeChatTabViews(iconView, labelView, badgeDot, badgeCount, tab))

                }

            }
        }.also {

            weChatCachedTabs = it

            weChatTabsCacheSource = source

        }



        val tabCount = tabs.size

        // slotTransform already centers source content within the pill.

        // We draw in source coordinate space. The pill height in source

        // coordinates is height / slotTransform.scale. Use that to

        // vertically center icon + spacing + text within the pill.

        val firstIcon = tabs.firstOrNull { it.icon != null }?.icon

        val iconScale = 1.15f * 1.1f * configIconScale
        val iconSize = (firstIcon?.height?.takeIf { it > 0 }?.toFloat()
            ?: (40f * density)) * iconScale

        val labelH = tabs.firstOrNull { it.label != null }?.label?.let {

            val bounds = textBounds
            val text = it.text.toString()
            it.paint.getTextBounds(text, 0, text.length, bounds)
            bounds.height().toFloat()

        } ?: (12f * density)

        // Position the visible glyphs, not the font's extra ascent/descent
        // space. Native icon bitmaps already contain transparent edge padding.
        val gap = 0f

        val totalH = iconSize + gap + labelH

        // pillHeight in source coords = height / slotTransform.scale

        val pillH = source.height.toFloat()

        // Move the icon/label group together; badges keep their row anchor.
        val baseY = if (iconOnly) ((hostHeight ?: pillH) - iconSize) * 0.5f else (pillH - totalH) * 0.5f - 2f * density

        val iconCenterY = baseY + iconSize * 0.5f

        val textCenterY = baseY + iconSize + gap + labelH * 0.5f



        val tabWidth = hostWidth.toFloat() / tabCount



        val normalLabelColor = if (selectedArtwork == false && !iconOnly) {
            val resources = source.resources
            if (foregroundResources !== resources) {
                foregroundResources = resources
                foregroundColorId = resources.getIdentifier("FG_0", "color", "com.tencent.mm")
            }
            val fallback = if (dark) android.graphics.Color.WHITE else 0xFF191919.toInt()
            if (foregroundColorId != 0) runCatching {
                resources.getColor(foregroundColorId, source.context.theme)
            }.getOrDefault(fallback) else fallback
        } else if (dark) android.graphics.Color.WHITE else 0xFF191919.toInt()

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

                // Match the scaled height used by the centred icon/label layout.
                // Badge coordinates remain independently anchored to the row.
                canvas.translate(tx, ty)
                canvas.scale(iconScale, iconScale, iW * 0.5f, iH * 0.5f)

                // Direct View.draw uses the local canvas. The parent normally
                // applies the View's alpha and visibility when drawing a child.

                // Decompiled TabIconView.onDraw blends three native bitmaps
                // using its single declared int (setFocusAlpha's backing field).
                // Record the real endpoint artwork, then restore native state
                // without invoking selection or invalidating WeChat's controller.
                val focus = if (selectedArtwork != null) {
                    val type = icon.javaClass
                    if (!focusFields.containsKey(type)) {
                        focusFields[type] = type.declaredFields.singleOrNull {
                            it.type == Int::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(it.modifiers)
                        }?.apply { isAccessible = true }
                    }
                    focusFields[type]
                } else null
                val nativeFocus = focus?.getInt(icon)
                try {
                    if (nativeFocus != null) focus.setInt(icon, if (selectedArtwork == true) 255 else 0)
                    icon.draw(canvas)
                } finally {
                    if (nativeFocus != null) focus.setInt(icon, nativeFocus)
                }

                canvas.restore()



                // Draw label text centered below icon

                val label = tc.label

                if (label != null && (!iconOnly || tc.icon == null)) {

                    val text = label.text

                    if (text != null && text.isNotEmpty()) {

                        val tp = labelPaint.apply { set(label.paint) }

                        tp.color = when (selectedArtwork) {
                            true -> 0xFF07C160.toInt()
                            false -> normalLabelColor
                            null -> label.currentTextColor
                        }

                        tp.alpha = 255

                        val nativeText = text.toString()
                        val s = if (managerEnglishLabels) NavigationLabelLocalization.toEnglish("wechat", nativeText) else nativeText

                        val tw = tp.measureText(s)

                        val lx = centerX - tw * 0.5f

                        val bounds = textBounds
                        tp.getTextBounds(s, 0, s.length, bounds)
                        val ly = textCenterY - (bounds.top + bounds.bottom) * 0.5f

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

                        // Anchor the badge's top to the row, just below the
                        // selection's 4dp top inset, independently of icon size.

                        val by = br + 6f * density

                        canvas.drawCircle(bx, by, br, badgePaint)

                        val cp = badgeTextPaint
                        cp.textSize = cnt.textSize

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

