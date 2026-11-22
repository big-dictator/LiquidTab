package io.github.offlineglass.hook.adapters.tieba

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import kotlin.math.roundToInt

/** Project the native row without its captions while preserving animated icons and badges. */
internal class TiebaBottomArtwork {
    private data class Glyphs(val idleId: Int, val selectedId: Int, val skin: Int,
                              val idle: Drawable, val selected: Drawable, var tint: Int? = null)
    private data class Slot(
        val index: Int, val indicator: View, val label: TextView, val iconFrame: ViewGroup, val badge: View?,
        var glyphs: Glyphs? = null,
    )

    private var source: ViewGroup? = null
    private var slots = emptyList<Slot>()
    private val rect = Rect()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconCenters = FloatArray(5) { Float.NaN }
    private var lastReportedSource: ViewGroup? = null
    private var lastMissingArtwork: ViewGroup? = null
    private var resolveAt = 0L
    private var retryArtworkAt = 0L
    private var colorSkin: Int? = null
    private var iconColor: Int? = null
    private var idleTextColor: Int? = null
    private var selectedTextColor: Int? = null
    var opticalReady = false
        private set

    fun prepareOptical(row: ViewGroup?) {
        if (row == null) { opticalReady = false; return }
        val now = SystemClock.uptimeMillis()
        if (!opticalReady && source === row && now < retryArtworkAt) return
        val bound = resolve(row)
        opticalReady = bound.isNotEmpty() && bound.all { slot ->
            val resources = TiebaNativeTabAnimation.resourcesFor(slot.indicator) ?: return@all false
            if (resources.idle <= 0 || resources.selected <= 0) return@all false
            val glyphs = slot.glyphs
            if (glyphs == null || glyphs.idleId != resources.idle ||
                glyphs.selectedId != resources.selected || glyphs.skin != resources.skin) {
                val idle = TiebaNativeTabAnimation.loadDrawable(row.context, resources.idle) ?: return@all false
                val selected = TiebaNativeTabAnimation.loadDrawable(row.context, resources.selected) ?: return@all false
                slot.glyphs = Glyphs(resources.idle, resources.selected, resources.skin, idle, selected)
            }
            true
        }
        if (!opticalReady) {
            retryArtworkAt = now + 240L
            if (bound.isNotEmpty() && lastMissingArtwork !== row) {
                lastMissingArtwork = row
                android.util.Log.w("LiquidTabTieba", "Missing native idle/selected tab artwork pair")
            }
            return
        }
        val skin = bound.first().glyphs?.skin
        if (colorSkin != skin) {
            colorSkin = skin
            iconColor = TiebaNativeTabAnimation.nativeColor(row.context, "CAM_X0105")
            idleTextColor = TiebaNativeTabAnimation.nativeColor(row.context, "CAM_X0619")
            selectedTextColor = TiebaNativeTabAnimation.nativeColor(row.context, "CAM_X0622")
        }
    }

    fun drawOptical(canvas: Canvas, frame: AdapterNavigationFrame, selected: Boolean) {
        val row = source ?: return
        val scale = frame.iconScale * TiebaAdapter.projectedNavigationScale * frame.extraScale
        val hostCenter = frame.height / 2f
        val defaultColor = if (frame.darkGlass) Color.WHITE else 0xFF222222.toInt()
        val labelColor = (if (selected) selectedTextColor else idleTextColor) ?: defaultColor
        slots.forEach { slot ->
            val visualIndex = frame.enabledIndices.indexOf(slot.index)
            if (visualIndex < 0 || slot.iconFrame.height <= 0 || slot.iconFrame.width <= 0) return@forEach
            val glyphs = slot.glyphs ?: return@forEach
            val drawable = if (selected) glyphs.selected else glyphs.idle
            val centerX = 4f * frame.density + (visualIndex + 0.5f) * frame.slotWidth
            val sourceCenterX = (slot.index + 0.5f) * row.width / frame.slotCount
            boundsInRow(row, slot.iconFrame)
            val iconCenter = rect.exactCenterY()
            val sourceCenterY = if (frame.iconOnly) iconCenter else row.height / 2f
            val box = minOf(rect.width(), rect.height()) * TiebaAdapter.TAB_ICON_SCALE * frame.iconScale
            val save = canvas.save()
            canvas.translate(centerX, hostCenter)
            canvas.scale(scale, scale)
            canvas.translate(-sourceCenterX, -sourceCenterY)
            val glyphCenterX = rect.exactCenterX()
            val ratio = drawable.intrinsicWidth.coerceAtLeast(1).toFloat() / drawable.intrinsicHeight.coerceAtLeast(1)
            val glyphWidth = minOf(box, box * ratio)
            val glyphHeight = minOf(box, box / ratio)
            val tint = iconColor ?: defaultColor
            if (glyphs.tint != tint) {
                glyphs.tint = tint
                glyphs.idle.setTint(tint)
                glyphs.selected.setTint(tint)
            }
            drawable.setBounds(
                (glyphCenterX - glyphWidth / 2f).roundToInt(), (iconCenter - glyphHeight / 2f).roundToInt(),
                (glyphCenterX + glyphWidth / 2f).roundToInt(), (iconCenter + glyphHeight / 2f).roundToInt(),
            )
            drawable.draw(canvas)
            if (!frame.iconOnly && slot.label.visibility == View.VISIBLE) {
                boundsInRow(row, slot.label)
                textPaint.set(slot.label.paint)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.color = labelColor
                canvas.drawText(slot.label.text.toString(), rect.exactCenterX(),
                    rect.top + slot.label.translationY + slot.label.baseline, textPaint)
            }
            slot.badge?.takeIf { it.isShown && it.width > 0 && it.height > 0 }?.let { badge ->
                boundsInRow(row, badge)
                canvas.save()
                canvas.translate(rect.left.toFloat(), rect.top.toFloat())
                badge.draw(canvas)
                canvas.restore()
            }
            canvas.restoreToCount(save)
        }
    }

    private fun boundsInRow(row: ViewGroup, view: View) {
        rect.set(0, 0, view.width, view.height)
        row.offsetDescendantRectToMyCoords(view, rect)
    }

    fun capture(row: ViewGroup, target: Bitmap): Boolean {
        val bound = resolve(row)
        if (bound.isEmpty()) return false
        val alpha = row.alpha
        val captions = bound.map { it.label to it.label.alpha }
        val canvas = Canvas(target)
        canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        try {
            if (alpha < 0.01f) row.alpha = 1f
            captions.forEach { (label, _) -> label.alpha = 0f }
            row.draw(canvas)
        } finally {
            captions.forEach { (label, original) -> label.alpha = original }
            if (alpha < 0.01f) row.alpha = alpha
        }

        iconCenters.fill(Float.NaN)
        bound.forEach { slot ->
            if (slot.iconFrame.height > 0) {
                boundsInRow(row, slot.iconFrame)
                iconCenters[slot.index] = rect.exactCenterY()
            }
        }
        return true
    }

    fun iconCenter(index: Int): Float? = iconCenters.getOrNull(index)?.takeIf(Float::isFinite)

    fun clear() {
        source = null
        slots = emptyList()
        lastReportedSource = null
        lastMissingArtwork = null
        colorSkin = null
        resolveAt = 0L
        retryArtworkAt = 0L
        opticalReady = false
        iconCenters.fill(Float.NaN)
    }

    private fun resolve(row: ViewGroup): List<Slot> {
        val now = SystemClock.uptimeMillis()
        if (source === row) {
            if (slots.isEmpty() && now < resolveAt) return slots
            if (slots.isNotEmpty() && slots.all { slot ->
                    row.getChildAt(slot.index) === slot.indicator &&
                        slot.label.parent === slot.iconFrame.parent && slot.label.parent?.parent === slot.indicator
                }) return slots
        }
        resolveAt = now + 240L
        source = row
        iconCenters.fill(Float.NaN)
        slots = if (row.childCount != 5) emptyList() else {
            val indicators = (0 until row.childCount).mapNotNull { index ->
                val view = row.getChildAt(index) as? ViewGroup ?: return@mapNotNull null
                if (generateSequence<Class<*>>(view.javaClass) { it.superclass }.none {
                        it.name == "com.baidu.tbadk.mainTab.MainTabCommonIndicator"
                    }) return@mapNotNull null
                index to view
            }
            val candidates = indicators.mapNotNull { (index, indicator) -> bindSlot(index, indicator) }
            candidates.takeIf { indicators.size in 4..5 && it.size == indicators.size }.orEmpty()
        }
        if (slots.isEmpty() && lastReportedSource !== row) {
            lastReportedSource = row
            android.util.Log.w("LiquidTabTieba", "Icon-only captions unavailable: native tab structure did not match")
        }
        return slots
    }

    private fun bindSlot(index: Int, indicator: ViewGroup): Slot? {
        val title = runCatching {
            (indicator.javaClass.getMethod("getText").invoke(indicator) as? CharSequence)?.toString()?.trim()
        }.getOrNull()?.takeIf(String::isNotEmpty) ?: return null
        val layout = (0 until indicator.childCount).map(indicator::getChildAt)
            .filterIsInstance<LinearLayout>()
            .singleOrNull { container -> container.children().any { it is TextView && it.text.toString().trim() == title } }
            ?: return null
        val label = layout.children().filterIsInstance<TextView>()
            .singleOrNull { it.text.toString().trim() == title } ?: return null
        val iconFrame = layout.children().filterIsInstance<ViewGroup>().singleOrNull { candidate ->
            candidate.children().any { it.javaClass.name == "com.baidu.tbadk.mainTab.HomeTabWebpIcon" }
        } ?: return null
        val badge = indicator.children().singleOrNull {
            it.javaClass.name == "com.baidu.tbadk.core.view.MessageRedDotView"
        }
        return Slot(index, indicator, label, iconFrame, badge)
    }

    private fun ViewGroup.children() = (0 until childCount).map(::getChildAt)
}
