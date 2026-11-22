package io.github.offlineglass.rendering

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.offlineglass.hook.setBottomBarSquircle
import kotlin.math.min

/** Parallel offsets of the bottom panel, shared by View and Compose hosts. */
internal object InsetIndicatorOutline {
    fun resting(
        out: Path, bounds: RectF, panelWidth: Float, panelHeight: Float, panelRadius: Float, curve: Float,
        buildPanel: (Path, RectF, Float, Float) -> Unit = { path, rect, radius, smoothing ->
            path.setBottomBarSquircle(rect, radius, smoothing)
        },
    ): Boolean {
        val inset = (panelHeight - bounds.height()) / 2f
        if (inset <= 0f || bounds.width() + 2f * inset > panelWidth) return false
        val cap = (panelRadius.coerceAtMost(min(panelWidth, panelHeight) / 2f) *
            (1f + 0.2f * curve.coerceIn(0f, 1f)) - inset).coerceAtLeast(0f)
        // Cut only through the straight portion of the inset panel.
        if (bounds.width() < 2f * cap || curve < -1f) return false
        val panel = Path()
        buildPanel(panel, RectF(0f, 0f, panelWidth, panelHeight), panelRadius, curve)
        val stroke = Path()
        val paint = strokePaint().apply { strokeWidth = 2f * inset }
        if (!paint.getFillPath(panel, stroke) || !panel.op(stroke, Path.Op.DIFFERENCE) || panel.isEmpty)
            return false
        val half = bounds.width() / 2f
        // Overlap the cuts so float translations don't leave two contours.
        val overlap = min(0.5f, (panelWidth - 2f * inset - bounds.width()) / 4f)
        val left = Path().apply { addRect(0f, 0f, inset + half + overlap, panelHeight, Path.Direction.CW) }
        val right = Path().apply { addRect(panelWidth - inset - half - overlap, 0f, panelWidth, panelHeight, Path.Direction.CW) }
        if (!left.op(panel, Path.Op.INTERSECT) || !right.op(panel, Path.Op.INTERSECT)) return false
        left.offset(bounds.left - inset, bounds.top - inset)
        right.offset(bounds.right - panelWidth + inset, bounds.top - inset)
        if (!left.op(right, Path.Op.UNION) || left.isEmpty) return false
        out.set(left)
        return true
    }

    fun strokePaint() = Paint().apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    fun expand(out: Path, base: Path, outset: Float, stroke: Path, paint: Paint): Boolean {
        out.set(base)
        paint.strokeWidth = 2f * outset
        stroke.rewind()
        return paint.getFillPath(base, stroke) && out.op(stroke, Path.Op.UNION)
    }
}
