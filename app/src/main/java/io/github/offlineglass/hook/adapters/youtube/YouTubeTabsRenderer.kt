package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig

/** Retains native artwork, without replaying recycled/hidden native Views. */
internal class YouTubeTabsRenderer {
    var opticalSelected: Boolean? = null
    private data class Artwork(val drawable: Drawable, val label: String, val typeface: Typeface?, val state: IntArray, val badge: String?)
    private val artwork = HashMap<Int, Artwork>()
    private var nextRefresh = 0L
    private var bindingIdentity: YouTubePivotBinding? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val avatarClip = android.graphics.Path()
    private val selectedState = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected, android.R.attr.state_activated)
    private val normalState = intArrayOf(android.R.attr.state_enabled)

    fun clear() { artwork.clear(); nextRefresh = 0L; bindingIdentity = null }

    private fun refresh(binding: YouTubePivotBinding) {
        val now = SystemClock.uptimeMillis()
        if (bindingIdentity !== binding) {
            artwork.clear()
            bindingIdentity = binding
        } else if (now < nextRefresh) return
        nextRefresh = now + 250L
        for (index in 0..4) {
            val entry = binding.entry(index) ?: continue
            val native = entry.icon.drawable ?: continue
            val copy = native.constantState?.newDrawable(entry.icon.resources, entry.icon.context.theme)?.mutate() ?: continue
            entry.icon.imageTintList?.let(copy::setTintList)
            native.colorFilter?.let(copy::setColorFilter)
            val countView = YouTubePivotBinding.child(entry.cell, "new_content_count") as? TextView
            val dot = YouTubePivotBinding.child(entry.cell, "new_content_dot")
            val badge = countView?.takeIf { it.visibility == View.VISIBLE && it.text.isNotBlank() }
                ?.text?.toString() ?: dot?.takeIf { it.visibility == View.VISIBLE }?.let { "" }
            artwork[index] = Artwork(copy, entry.text?.text?.toString()?.takeIf(String::isNotBlank) ?: entry.label,
                entry.text?.typeface, native.state.copyOf(), badge)
        }
    }

    fun drawSlot(canvas: Canvas, binding: YouTubePivotBinding, index: Int,
                 centerX: Float, centerY: Float, iconScale: Float, config: GlassConfig,
                 opticalSelected: Boolean? = null, dark: Boolean = true,
                 iconOnly: Boolean = config.iconOnly,
                 localizeLabel: (String) -> String = { it }): Boolean {
        refresh(binding)
        val item = artwork[index] ?: return false
        val source = binding.source
        val d = source.resources.displayMetrics.density
        val sd = source.resources.displayMetrics.scaledDensity
        val size = 24f * d * 1.08f * iconScale
        val labelSize = config.textSize * sd * 1.08f * iconScale / config.iconScale.coerceAtLeast(.01f)
        textPaint.textSize = labelSize; textPaint.typeface = item.typeface
        textPaint.color = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        val fm = textPaint.fontMetrics
        val labelHeight = if (iconOnly) 0f else fm.descent - fm.ascent
        val gap = if (iconOnly) 0f else 2f*d
        val top = centerY - (size + gap + labelHeight)/2f
        val drawable = item.drawable
        drawable.state = when (opticalSelected) {
            true -> selectedState
            false -> normalState
            null -> item.state
        }
        drawable.alpha = 255
        drawable.setBounds((centerX-size/2f).toInt(), top.toInt(), (centerX+size/2f).toInt(), (top+size).toInt())
        if (index == 4) {
            // The native profile artwork is a square bitmap. Crop only its
            // icon pixels to a circular viewport, without changing the label,
            // badge, or other tab artwork.
            val save = canvas.save()
            avatarClip.rewind()
            avatarClip.addCircle(centerX, top + size / 2f, size / 2f, android.graphics.Path.Direction.CW)
            canvas.clipPath(avatarClip)
            drawable.draw(canvas)
            canvas.restoreToCount(save)
        } else {
            drawable.draw(canvas)
        }
        if (!iconOnly) canvas.drawText(localizeLabel(item.label), centerX, top+size+gap-fm.ascent, textPaint)
        item.badge?.let { badge ->
            val x = centerX + size*.42f; val y = top + size*.08f
            textPaint.color = 0xFFFF0033.toInt()
            canvas.drawCircle(x, y, if (badge.isEmpty()) 3f*d else 5.5f*d, textPaint)
            if (badge.isNotEmpty()) {
                textPaint.color = android.graphics.Color.WHITE; textPaint.textSize = 7f*sd
                canvas.drawText(badge.take(2), x, y-(textPaint.fontMetrics.ascent+textPaint.fontMetrics.descent)/2f, textPaint)
            }
        }
        return true
    }
}
