package io.github.offlineglass.hook.adapters.douyin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame

/** Douyin-owned TikTok artwork; shared host supplies only generic frame geometry. */
internal class DouyinNavigationRenderer(private val context: Context) {
    private val defaultIcons = arrayOfNulls<Bitmap>(5)
    private val selectedIcons = arrayOfNulls<Bitmap>(5)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = with(frame) {
        if (width <= 0 || height <= 0) return
        val names = arrayOf("home", "friends", "publish", "inbox", "profile")
        val moduleAssets = runCatching {
            context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY).assets
        }.getOrNull()
        val defaults = names.mapIndexed { i, n -> defaultIcons[i] ?: moduleAssets?.let { assets ->
            runCatching { assets.open("tiktok/$n.png").use(BitmapFactory::decodeStream) }.getOrNull()
                ?.also { defaultIcons[i] = it }
        } }
        val selected = names.mapIndexed { i, n ->
            if (i == 2) defaults[i] else selectedIcons[i] ?: moduleAssets?.let { assets ->
                runCatching { assets.open("tiktok/${n}_fill.png").use(BitmapFactory::decodeStream) }.getOrNull()
                    ?.also { selectedIcons[i] = it }
            }
        }
        if (defaults.all { it != null } && selected.all { it != null }) {
            val sw = slotWidth
            val pad = 4f * density
            val iconHeight = 29f * density
            labelPaint.textSize = textSize * scaledDensity
            val baseIconCenterY = height * .30f
            val baseLabelBaseline = height * .78f
            val groupTop = baseIconCenterY - iconHeight / 2f
            val groupBottom = baseLabelBaseline + labelPaint.fontMetrics.bottom
            val groupOffsetY = height / 2f - (groupTop + groupBottom) / 2f
            val iconCenterY = baseIconCenterY + groupOffsetY
            val labelBaseline = baseLabelBaseline + groupOffsetY
            val labels = arrayOf("首页", "朋友", "", "消息", "我")
            for (slot in 0..4) {
                val centerX = pad + (slot + .5f) * sw
                canvas.save()
                canvas.scale(extraScale, extraScale, centerX, height / 2f)
                val bitmap = (if (slot == selectedIndex) selected[slot] else defaults[slot])!!
                val drawHeight = if (slot == 2) iconHeight * 1.8f else iconHeight
                val drawCenterY = if (slot == 2) height / 2f else iconCenterY
                val ratio = bitmap.width.toFloat() / bitmap.height
                val selectedInOpticalLayer = navigationOpticalMix > 0f
                val drawSelectedGroup = !hideSelectedForOptics ||
                    slot != selectedIndex || selectedInOpticalLayer
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
                    labelPaint.color = if (slot == selectedIndex) Color.WHITE else Color.rgb(205, 203, 210)
                    canvas.drawText(labels[slot], centerX, labelBaseline, labelPaint)
                }
                canvas.restore()
            }
            return
        }

        if (width <= 0 || height <= 0) return
        val slotWidth = slotWidth
        val pad = 4f * density
        val iconSize = 23f * density
        val iconY = height * 0.31f
        val labelY = height * 0.78f
        val labels = arrayOf("首页", "朋友", "消息", "我")
        val selectedColor = Color.WHITE
        val normalColor = if (darkGlass) Color.rgb(205, 203, 210) else Color.rgb(44, 44, 44)
        labelPaint.textSize = textSize * scaledDensity
        val publishCx = pad + 2.5f * slotWidth
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
        for (i in 0 until 4) {
            val slot = if (i < 2) i else i + 1
            val cx = pad + (slot + 0.5f) * slotWidth
            canvas.save()
            canvas.scale(extraScale, extraScale, cx, height / 2f)
            iconPaint.color = if (slot == selectedIndex) selectedColor else normalColor
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = 1.8f * density
            iconPaint.strokeCap = Paint.Cap.ROUND
            iconPaint.strokeJoin = Paint.Join.ROUND
            canvas.save(); canvas.translate(cx, iconY)
            canvas.scale(iconSize / 24f, iconSize / 24f)
            when (i) {
                0 -> { iconPaint.style = Paint.Style.FILL; canvas.drawPath(parsePath("M2 10 12 2l10 8v10H14v-6h-4v6H2z"), iconPaint) }
                1 -> { canvas.drawCircle(-4f, -5f, 3.5f, iconPaint); canvas.drawCircle(5f, -4f, 3f, iconPaint); canvas.drawArc(RectF(-10f, 1f, 2f, 13f), 205f, 130f, false, iconPaint); canvas.drawArc(RectF(0f, 2f, 10f, 13f), 205f, 130f, false, iconPaint) }
                2 -> { canvas.drawRoundRect(RectF(-9f, -9f, 9f, 7f), 1.5f, 1.5f, iconPaint); canvas.drawLine(-4f, 7f, -4f, 11f, iconPaint); canvas.drawLine(-4f, 11f, 1f, 7f, iconPaint); canvas.drawLine(-5f, -1f, 5f, -1f, iconPaint) }
                else -> { canvas.drawCircle(0f, -5f, 4f, iconPaint); canvas.drawArc(RectF(-9f, 1f, 9f, 15f), 205f, 130f, false, iconPaint) }
            }
            canvas.restore()
            iconPaint.style = Paint.Style.FILL
            labelPaint.color = if (slot == selectedIndex) selectedColor else normalColor
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

    private fun parsePath(data: String): Path = PathParser.createPathFromPathData(data) ?: Path()

    private companion object { const val MODULE_PACKAGE = "io.github.offlineglass" }
}
