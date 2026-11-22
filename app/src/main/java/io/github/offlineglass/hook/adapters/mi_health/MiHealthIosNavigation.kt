package io.github.offlineglass.hook.adapters.mi_health

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import java.lang.ref.WeakReference

/** Native tabs own routes, labels, geometry and unread state. Only artwork changes. */
internal class MiHealthIosNavigation {
    private data class Cell(val index: Int, val tab: ViewGroup, val icon: ImageView, val label: TextView, val dot: ImageView,
        val originalDotFilter: ColorFilter?)
    private var source: WeakReference<ViewGroup>? = null
    private var cells = emptyList<Cell>()
    private var lastProbe = 0L
    private var reportedFailure = false
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val relative = Matrix()
    private val inverse = Matrix()
    private val iconCenter = FloatArray(2)
    private val red = PorterDuffColorFilter(0xFFFF3B30.toInt(), PorterDuff.Mode.SRC_IN)

    fun update(host: GlassHostLayout) {
        val native = host.navigationSource ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (source?.get() !== native || cells.any { !it.icon.isAttachedToWindow } || cells.size != 4) {
            if (now - lastProbe < 600L) return
            lastProbe = now
            val next = runCatching { bind(native) }.getOrElse {
                if (!reportedFailure) { reportedFailure = true; android.util.Log.w("MiHealthIosTabs", "Native tab contract unavailable", it) }
                return
            }
            dispose(); source = WeakReference(native); cells = next
        }
        cells.forEach { if (it.dot.colorFilter !== red) it.dot.colorFilter = red }
    }

    private fun bind(native: ViewGroup): List<Cell> {
        check(native.javaClass.name == "com.google.android.material.tabs.TabLayout")
        check(native.resources.getResourceEntryName(native.id) == "main_tl_bottom")
        val collection = native.createAccessibilityNodeInfo()
        val count = collection.collectionInfo?.columnCount
        collection.recycle()
        check(count == 4) { "Expected four logical native columns" }
        val result = LinkedHashMap<Int, Cell>()
        val stack = ArrayDeque<View>(); stack += native
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 96) {
            val view = stack.removeLast()
            if (view is ViewGroup && view !== native) {
                val node = view.createAccessibilityNodeInfo()
                val item = node.collectionItemInfo
                val index = item?.columnIndex
                val valid = item != null && item.rowIndex == 0 && item.rowSpan == 1 && item.columnSpan == 1
                node.recycle()
                if (valid && index != null && index in 0..3) {
                    fun named(name: String): View? {
                        val id = native.resources.getIdentifier(name, "id", native.context.packageName)
                        return if (id != 0) view.findViewById(id) else null
                    }
                    val icon = named("icon") as? ImageView ?: error("Missing tab icon")
                    val label = named("main_tv_tab_name") as? TextView ?: error("Missing tab label")
                    val dot = named("red_point") as? ImageView ?: error("Missing unread dot")
                    check(index !in result) { "Ambiguous native column" }
                    result[index] = Cell(index, view, icon, label, dot, dot.colorFilter)
                    continue
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        check(stack.isEmpty() && result.keys == setOf(0, 1, 2, 3)) { "Incomplete native tab contract" }
        check(result.values.map { it.icon }.distinct().size == 4)
        return result.toSortedMap().values.toList()
    }

    fun performTap(host: GlassHostLayout, index: Int): Boolean {
        update(host)
        if (source?.get() !== host.navigationSource) return false
        val tab = cells.singleOrNull { it.index == index }?.tab ?: return false
        return tab.isAttachedToWindow && tab.isEnabled && tab.isClickable && tab.performClick()
    }

    fun draw(canvas: Canvas, host: GlassHostLayout, frame: AdapterNavigationFrame) {
        val native = source?.get()
        val useIosArtwork = host.config.miHealthIosIcons
        if ((!useIosArtwork && !frame.iconOnly) || native == null || native !== host.navigationSource || cells.size != 4) {
            host.drawOriginalNavigationForAdapter(canvas, frame.extraScale)
            return
        }
        inverse.reset(); native.transformMatrixToGlobal(inverse)
        if (!inverse.invert(inverse)) return
        cells.filter { it.index in frame.enabledIndices }.forEach { cell ->
            val transform = host.adapterTabTransform(cell.index) ?: return@forEach
            val visual = frame.enabledIndices.indexOf(cell.index)
            val cx = 4f * frame.density + (visual + .5f) * frame.slotWidth
            val saved = canvas.save()
            canvas.scale(frame.extraScale, frame.extraScale, cx, frame.height / 2f)
            if (frame.iconOnly) {
                relative.reset(); cell.icon.transformMatrixToGlobal(relative)
                relative.setConcat(inverse, relative)
                iconCenter[0] = cell.icon.width / 2f
                iconCenter[1] = cell.icon.height / 2f
                relative.mapPoints(iconCenter)
                // Move the artwork and its unread dot together in host pixels.
                canvas.translate(0f, frame.height / 2f - (transform[2] + iconCenter[1] * transform[0]))
            }
            canvas.translate(transform[1], transform[2]); canvas.scale(transform[0], transform[0])
            // Both complete scenes have identical native geometry. The shared
            // final lens mask chooses selected/idle per pixel, never per page.
            val selected = if (frame.navigationOpticalMix > 0f) frame.hideSelectedForOptics
                else cell.index == frame.selectedIndex
            val color = if (selected) frame.accentColor
                else if (frame.darkGlass) 0xFFB2B2B7.toInt() else 0xFF68686E.toInt()
            val iconSave = canvas.save()
            concatView(canvas, cell.icon)
            if (useIosArtwork) {
                val shape = MiHealthIosSymbols.shape(cell.index, selected)
                val scale = minOf(cell.icon.width / shape.width, cell.icon.height / shape.height)
                canvas.translate((cell.icon.width - shape.width * scale) / 2f, (cell.icon.height - shape.height * scale) / 2f)
                canvas.scale(scale, scale)
                shape.parts.forEach { part ->
                    ink.color = color; ink.alpha = (part.opacity * 255f).toInt()
                    canvas.drawPath(part.path, ink)
                }
            } else {
                cell.icon.draw(canvas)
            }
            canvas.restoreToCount(iconSave)
            if (!frame.iconOnly) {
                val textSave = canvas.save(); concatView(canvas, cell.label)
                labelPaint.set(cell.label.paint); labelPaint.color = color
                labelPaint.alpha = (cell.label.alpha * 255f).toInt(); labelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(cell.label.text.toString(), cell.label.width / 2f, cell.label.baseline.toFloat(), labelPaint)
                canvas.restoreToCount(textSave)
            }
            if (cell.dot.visibility == View.VISIBLE) {
                val dotSave = canvas.save(); concatView(canvas, cell.dot)
                ink.color = 0xFFFF3B30.toInt(); ink.alpha = 255
                canvas.drawCircle(cell.dot.width / 2f, cell.dot.height / 2f, minOf(cell.dot.width, cell.dot.height) / 2f, ink)
                canvas.restoreToCount(dotSave)
            }
            canvas.restoreToCount(saved)
        }
    }

    private fun concatView(canvas: Canvas, view: View) {
        relative.reset(); view.transformMatrixToGlobal(relative)
        relative.setConcat(inverse, relative)
        canvas.concat(relative)
    }

    fun dispose() {
        cells.forEach { it.dot.colorFilter = it.originalDotFilter }
        cells = emptyList(); source = null
    }
}
