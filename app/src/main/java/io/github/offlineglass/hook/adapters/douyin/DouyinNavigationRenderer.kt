package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame

/** Douyin-owned TikTok artwork; shared host supplies only generic frame geometry. */
internal class DouyinNavigationRenderer(private val context: Context) {
    private val defaultIcons = arrayOfNulls<Bitmap>(5)
    private val selectedIcons = arrayOfNulls<Bitmap>(5)
    private var loadedDefaults: List<Bitmap?>? = null
    private var loadedSelected: List<Bitmap?>? = null
    private var artworkFailureReported = false
    private fun artwork(name: String): Bitmap? = runCatching {
        val bytes = requireNotNull(DouyinIconArtwork.bytes(name))
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }.onFailure {
        if (!artworkFailureReported) {
            artworkFailureReported = true
            android.util.Log.w("LiquidTab", "Douyin embedded artwork unavailable: $name", it)
        }
    }.getOrNull()
    private val iconNames = arrayOf("home", "friends", "publish", "inbox", "profile")
    private val lightSelectedColor = Color.rgb(44, 44, 44)
    private val lightNormalColor = Color.rgb(180, 178, 185)
    private val darkSelectedTint = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    private val darkNormalTint = PorterDuffColorFilter(Color.rgb(205, 203, 210), PorterDuff.Mode.SRC_IN)
    private val lightSelectedTint = PorterDuffColorFilter(lightSelectedColor, PorterDuff.Mode.SRC_IN)
    private val lightNormalTint = PorterDuffColorFilter(lightNormalColor, PorterDuff.Mode.SRC_IN)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = with(frame) {
        if (width <= 0 || height <= 0) return
        val visibleSlots = enabledIndices.toIntArray()
        val useLightNavigation = !darkGlass && (selectedIndex == 3 || selectedIndex == 4)
        val names = iconNames
        val defaults = loadedDefaults ?: names.mapIndexed { i, n ->
            defaultIcons[i] ?: artwork(n)?.also { defaultIcons[i] = it }
        }
        val selected = loadedSelected ?: names.mapIndexed { i, n ->
            if (i == 2) defaults[i] else selectedIcons[i]
                ?: artwork("${n}_fill")?.also { selectedIcons[i] = it } ?: defaults[i]
        }
        val required = names.indices.filter { it != 2 || barLayout == null }
        if (required.all { defaults[it] != null && selected[it] != null }) {
            loadedDefaults = defaults
            loadedSelected = selected
            val sw = slotWidth
            val pad = 4f * density
            val iconHeight = 29f * density
            labelPaint.textSize = textSize * scaledDensity
            val baseIconCenterY = height * .30f
            val baseLabelBaseline = height * .78f
            val groupOffsetY = DouyinIconLayout.groupOffset(height.toFloat(), iconHeight,
                labelPaint.fontMetrics.bottom, iconOnly)
            val iconCenterY = baseIconCenterY + groupOffsetY
            val labelBaseline = baseLabelBaseline + groupOffsetY
            val labels = arrayOf("首页", "朋友", "", "消息", "我")
            for (slot in 0..4) {
                if (slot !in enabledIndices && slot != barLayout?.actionIndex) continue
                val centerX = barLayout?.let { layout ->
                    if (slot == layout.actionIndex) layout.actionLeft + layout.actionSize / 2f
                    else DouyinIconLayout.center(slot, visibleSlots, pad, sw)
                } ?: DouyinIconLayout.center(slot, visibleSlots, pad, sw)
                if (slot == 2 && barLayout != null) {
                    if (!hideSelectedForOptics) drawDetachedPublish(canvas, frame, centerX)
                    continue
                }
                canvas.save()
                canvas.scale(extraScale, extraScale, centerX, height / 2f)
                val geometricMask = navigationOpticalMix > 0f
                val accented = if (geometricMask) hideSelectedForOptics else slot == selectedIndex
                val bitmap = (if (accented) selected[slot] else defaults[slot])!!
                // Tint the original alpha artwork; keep the publish button's
                // cyan/red accents and the existing dark-mode artwork intact.
                iconPaint.colorFilter = when {
                    slot == 2 -> null
                    useLightNavigation -> if (accented) lightSelectedTint else lightNormalTint
                    geometricMask -> if (accented) darkSelectedTint else darkNormalTint
                    else -> null
                }
                val drawHeight = if (slot == 2) {
                    if (barLayout != null) minOf(iconHeight * 1.8f, barLayout.actionSize * .70f) else iconHeight * 1.8f
                } else iconHeight
                val drawCenterY = if (slot == 2) height / 2f else iconCenterY
                val ratio = bitmap.width.toFloat() / bitmap.height
                val drawSelectedGroup = true
                if (drawSelectedGroup) {
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(
                            centerX - drawHeight * ratio / 2f,
                            drawCenterY - drawHeight / 2f,
                            centerX + drawHeight * ratio / 2f,
                            drawCenterY + drawHeight / 2f,
                        ),
                        iconPaint,
                    )
                }
                if (drawSelectedGroup && slot != 2 && !iconOnly) {
                    labelPaint.color = if (accented) {
                        if (useLightNavigation) lightSelectedColor else Color.WHITE
                    } else if (useLightNavigation) lightNormalColor else Color.rgb(205, 203, 210)
                    canvas.drawText(labels[slot], centerX, labelBaseline, labelPaint)
                }
                canvas.restore()
            }
            return
        }

        if (width <= 0 || height <= 0) return
        val slotWidth = slotWidth
        val pad = 4f * density
        val iconSize = 29f * density
        val labels = arrayOf("首页", "朋友", "消息", "我")
        val selectedColor = if (useLightNavigation) lightSelectedColor else Color.WHITE
        val normalColor = if (useLightNavigation) lightNormalColor else Color.rgb(205, 203, 210)
        iconPaint.colorFilter = null
        labelPaint.textSize = textSize * scaledDensity
        val groupOffset = DouyinIconLayout.groupOffset(height.toFloat(), iconSize,
            labelPaint.fontMetrics.bottom, iconOnly)
        val iconY = height * .30f + groupOffset
        val labelY = height * .78f + groupOffset
        val publishCx = barLayout?.let { it.actionLeft + it.actionSize / 2f }
            ?: DouyinIconLayout.center(2, visibleSlots, pad, slotWidth)
        if (barLayout != null) {
            if (!hideSelectedForOptics) drawDetachedPublish(canvas, frame, publishCx)
        } else if (!publishCx.isNaN()) {
        canvas.save()
        canvas.scale(extraScale, extraScale, publishCx, height / 2f)
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = Color.WHITE
        canvas.drawRoundRect(RectF(publishCx - 21f * density, height / 2f - 14f * density,
            publishCx + 21f * density, height / 2f + 14f * density), 9f * density, 9f * density, iconPaint)
        iconPaint.color = Color.rgb(37, 211, 232)
        canvas.drawRoundRect(RectF(publishCx - 23f * density, height / 2f - 14f * density,
            publishCx + 17f * density, height / 2f + 14f * density), 9f * density, 9f * density, iconPaint)
        iconPaint.color = Color.rgb(245, 58, 115)
        canvas.drawRoundRect(RectF(publishCx - 17f * density, height / 2f - 14f * density,
            publishCx + 23f * density, height / 2f + 14f * density), 9f * density, 9f * density, iconPaint)
        iconPaint.color = Color.WHITE
        canvas.drawRoundRect(RectF(publishCx - 19f * density, height / 2f - 13f * density,
            publishCx + 19f * density, height / 2f + 13f * density), 8f * density, 8f * density, iconPaint)
        iconPaint.style = Paint.Style.STROKE
        iconPaint.color = Color.rgb(35, 35, 40)
        iconPaint.strokeWidth = 2.6f * density
        iconPaint.strokeCap = Paint.Cap.SQUARE
        canvas.drawLine(publishCx - 8f * density, height / 2f, publishCx + 8f * density, height / 2f, iconPaint)
        canvas.drawLine(publishCx, height / 2f - 8f * density, publishCx, height / 2f + 8f * density, iconPaint)
        canvas.restore()
        }
        for (i in 0 until 4) {
            val slot = if (i < 2) i else i + 1
            if (slot !in enabledIndices) continue
            val cx = DouyinIconLayout.center(slot, visibleSlots, pad, slotWidth)
            canvas.save()
            canvas.scale(extraScale, extraScale, cx, height / 2f)
            val accented = if (navigationOpticalMix > 0f) hideSelectedForOptics else slot == selectedIndex
            iconPaint.color = if (accented) selectedColor else normalColor
            iconPaint.style = Paint.Style.STROKE
            // Coordinates below are in a 24-unit viewport; Canvas supplies density.
            iconPaint.strokeWidth = 1.8f
            iconPaint.strokeCap = Paint.Cap.ROUND
            iconPaint.strokeJoin = Paint.Join.ROUND
            canvas.save(); canvas.translate(cx, iconY)
            canvas.scale(iconSize / 24f, iconSize / 24f)
            when (i) {
                0 -> { iconPaint.style = Paint.Style.FILL; canvas.translate(-12f, -11f); canvas.drawPath(parsePath("M2 10 12 2l10 8v10H14v-6h-4v6H2z"), iconPaint) }
                1 -> { canvas.drawCircle(-4f, -5f, 3.5f, iconPaint); canvas.drawCircle(5f, -4f, 3f, iconPaint); canvas.drawArc(RectF(-10f, 1f, 2f, 13f), 205f, 130f, false, iconPaint); canvas.drawArc(RectF(0f, 2f, 10f, 13f), 205f, 130f, false, iconPaint) }
                2 -> { canvas.drawRoundRect(RectF(-9f, -9f, 9f, 7f), 1.5f, 1.5f, iconPaint); canvas.drawLine(-4f, 7f, -4f, 11f, iconPaint); canvas.drawLine(-4f, 11f, 1f, 7f, iconPaint); canvas.drawLine(-5f, -1f, 5f, -1f, iconPaint) }
                else -> { canvas.drawCircle(0f, -5f, 4f, iconPaint); canvas.drawArc(RectF(-9f, 1f, 9f, 15f), 205f, 130f, false, iconPaint) }
            }
            canvas.restore()
            iconPaint.style = Paint.Style.FILL
            labelPaint.color = if (accented) selectedColor else normalColor
            if (!iconOnly) canvas.drawText(labels[i], cx, labelY, labelPaint)
            canvas.restore()
        }
    }

    fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ) = with(frame) {
        if (navigationOpticalMix <= 0f || selectionPath.isEmpty) {
            drawSource()
            return@with
        }
        val saved = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(saved)
        if (navigationOpticalMix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            val layer = canvas.saveLayerAlpha(
                0f, 0f, width.toFloat(), height.toFloat(),
                ((1f - navigationOpticalMix) * 255f).toInt().coerceIn(0, 255),
            )
            drawSource()
            canvas.restoreToCount(layer)
            canvas.restoreToCount(inside)
        }
    }

    /** Shared action surface uses the same configured fill as the main bar. */
    private fun drawDetachedPublish(canvas: Canvas, frame: AdapterNavigationFrame, centerX: Float) {
        val layout = frame.barLayout ?: return
        // Fixed geometry: never apply the left slider's press scale here.
        val half = minOf(9f * frame.density, layout.actionSize * .17f)
        val centerY = frame.height / 2f
        iconPaint.colorFilter = null
        iconPaint.style = Paint.Style.STROKE
        iconPaint.color = Color.WHITE
        iconPaint.strokeWidth = 2.6f * frame.density
        iconPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(centerX - half, centerY, centerX + half, centerY, iconPaint)
        canvas.drawLine(centerX, centerY - half, centerX, centerY + half, iconPaint)
        iconPaint.style = Paint.Style.FILL
    }

    private fun parsePath(data: String): Path = PathParser.createPathFromPathData(data) ?: Path()

    private companion object { const val MODULE_PACKAGE = "io.github.offlineglass" }
}






