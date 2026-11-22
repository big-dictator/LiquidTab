package io.github.offlineglass.hook.adapters.meituan_main

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.SLOT_COUNT
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.VIDEO
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.XIAOTUAN

/** Two immutable visual states, independent of the real page selection.
 * Android setSelected dispatches to the native visual-state implementation.
 * Capture is UI-only and restores native flags before any event can dispatch. */
internal class MeituanMainStateArtwork {
    private data class Pair(val idle: MeituanMainTabsRenderer, val active: MeituanMainTabsRenderer,
        val width: Int, val height: Int)
    @Volatile private var pair: Pair? = null
    private var source: ViewGroup? = null
    private var signature = Long.MIN_VALUE
    private var attemptedSource: ViewGroup? = null
    private var attemptedSignature = Long.MIN_VALUE
    val ready: Boolean get() = pair != null
    private var issue: String? = null
    private fun failed(reason: String): Boolean {
        // Keep the last verified complete frame through temporary native layout loss.
        if (issue != reason) android.util.Log.w("MeituanBinding", reason)
        issue = reason
        return false
    }

    private fun setCaptureState(glyph: View, active: Boolean) {
        glyph.isSelected = active
        check(glyph.isSelected == active) { "native selected property did not commit" }
        glyph.refreshDrawableState()
        // StateListDrawable can retain its outgoing selected image during a
        // fade. A cached idle frame must contain the terminal idle art, never
        // an in-flight blend that can outlive the native transition forever.
        for (getter in arrayOf("getIcon", "getBadgeIcon", "getBadge")) {
            val drawable = runCatching { XposedHelpers.callMethod(glyph, getter) as? Drawable }.getOrNull()
                ?: continue
            drawable.state = glyph.drawableState
            drawable.jumpToCurrentState()
        }
        glyph.jumpDrawablesToCurrentState()
    }

    fun prepare(row: ViewGroup?): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (row == null || row.width <= 0 || row.height <= 0)
            return failed("native navigation source missing or not laid out")
        if (source !== row) { signature = Long.MIN_VALUE }
        val native = MeituanMainNativeTabs.bind(row) ?: return failed("native navigation binding unavailable")
        val cells = native.cells.filter { it.role != VIDEO }
        val bounds = cells.associate { cell ->
            cell.role to Rect(cell.slot.left, 0, cell.slot.right, row.height)
        }
        if (bounds.values.any { it.left < 0 || it.right > row.width || it.isEmpty } ||
            bounds.values.sortedBy { it.left }.zipWithNext().any { (left, right) -> left.right > right.left })
            return failed("native navigation cell bounds unavailable or overlapping")
        var key = row.width.toLong() * 31 + row.height
        for (cell in cells) {
            val glyph = cell.glyph
            key = key * 31 + cell.role
            key = key * 31 + bounds.getValue(cell.role).hashCode()
            key = key * 31 + System.identityHashCode(glyph)
            key = key * 31 + cell.slot.visibility
            key = key * 31 + glyph.visibility
            key = key * 31 + glyph.width
            key = key * 31 + glyph.height
            val icon = runCatching { XposedHelpers.callMethod(glyph, "getIcon") as? android.graphics.drawable.Drawable }.getOrNull()
            key = key * 31 + System.identityHashCode(icon)
            key = key * 31 + System.identityHashCode(icon?.current)
            key = key * 31 + if (glyph.isSelected) 1 else 0
            key = key * 31 + (if (icon is StateListDrawable) icon.stateCount else 0)
            key = key * 31 + (runCatching { XposedHelpers.callMethod(glyph, "getTitle")?.hashCode() }.getOrNull() ?: 0)
            key = key * 31 + (runCatching { XposedHelpers.callMethod(glyph, "getBadgeText")?.hashCode() }.getOrNull() ?: 0)
            key = key * 31 + System.identityHashCode(runCatching { XposedHelpers.callMethod(glyph, "getBadgeIcon") }.getOrNull())
        }
        if (source === row && signature == key && pair != null) return false
        // A failed capture is retried only when its native inputs change,
        // never continuously as a compatibility fallback during scrolling.
        if (attemptedSource === row && attemptedSignature == key) return false
        attemptedSource = row; attemptedSignature = key
        val selected = cells.map { it.glyph.isSelected }
        val alpha = row.alpha
        var restored = true
        val result = runCatching {
            row.alpha = 1f
            fun capture(active: Boolean): MeituanMainTabsRenderer {
                cells.forEach { cell ->
                    // Xiaotuan is a branded action: retain its complete native art.
                    if (cell.role != XIAOTUAN) setCaptureState(cell.glyph, active)
                }
                val bitmap = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                // The native wrapper owns delegated artwork/animation. Drawing
                // its leaf directly bypasses that strategy and can yield blank art.
                row.draw(Canvas(bitmap))
                return MeituanMainTabsRenderer().apply {
                    check(accept(bitmap, bounds)) { "native artwork missing from a visible role" }
                }
            }
            Pair(capture(false), capture(true), row.width, row.height)
        }
        try {
            cells.forEachIndexed { index, cell ->
                if (cell.role != XIAOTUAN) try {
                    setCaptureState(cell.glyph, selected[index])
                } catch (error: Exception) {
                    restored = false
                    android.util.Log.e("MeituanBinding", "native selected-state restoration failed; takeover cancelled", error)
                }
            }
        } finally { row.alpha = alpha }
        if (!restored) return failed("native state restoration could not be verified")
        val captured = result.getOrElse { return failed("native artwork capture failed: ${it.javaClass.simpleName}") }
        pair = captured; source = row; signature = key; issue = null
        return true
    }

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame, active: Boolean) {
        val art = pair ?: return
        val renderer = if (active) art.active else art.idle
        val referenceWidth = art.width.toFloat() / SLOT_COUNT
        val scale = minOf(frame.slotWidth / referenceWidth, frame.height / art.height.toFloat()) * frame.extraScale
        frame.barLayout?.takeIf { !active }?.let { layout ->
            renderer.draw(canvas, layout.actionIndex, layout.actionLeft + layout.actionSize / 2f,
                frame.height / 2f, minOf(layout.actionSize / referenceWidth,
                    frame.height / art.height.toFloat()) * frame.extraScale,
                maxIconSize = layout.actionSize * .78f)
        }
        frame.enabledIndices.forEachIndexed { slot, index ->
            val left = 4f * frame.density + slot * frame.slotWidth
            val save = canvas.save()
            canvas.clipRect(left, 0f, left + frame.slotWidth, frame.height.toFloat())
            renderer.draw(canvas, index, left + frame.slotWidth / 2f, frame.height / 2f, scale,
                iconOnly = frame.iconOnly)
            canvas.restoreToCount(save)
        }
    }

    fun clear() {
        pair = null; source = null; signature = Long.MIN_VALUE
        attemptedSource = null; attemptedSignature = Long.MIN_VALUE
    }
}
