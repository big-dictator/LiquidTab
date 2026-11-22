package io.github.offlineglass.ui

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.InsetIndicatorOutline
import kotlin.math.min

/** Base geometry survives translation and press frames. Visible and optical
 * users share one path for the actual, pixel-rounded lens size. */
internal class ManagerInsetIndicatorShape(
    panelWidth: Float,
    panelHeight: Float,
    panelRadius: Float,
    private val curve: Float,
    inset: Float,
    tabs: Int,
) : Shape {
    private val base = RectF(0f, 0f, (panelWidth - 2f * inset).coerceAtLeast(1f) / tabs,
        (panelHeight - 2f * inset).coerceAtLeast(1f))
    private val radius = (panelRadius.coerceAtMost(min(panelWidth, panelHeight) / 2f) -
        inset).coerceIn(0f, min(base.width(), base.height()) / 2f)
    private val resting = Path()
    private val insetBuilt = InsetIndicatorOutline.resting(resting, base, panelWidth, panelHeight, panelRadius, curve)
    init {
        if (!insetBuilt) resting.setBottomBarSquircle(base, maxOf(radius, min(base.width(), base.height()) / 4f), curve)
    }
    private val stroke = Path()
    private val paint = InsetIndicatorOutline.strokePaint()
    private val matrix = Matrix()
    private var cached: Frame? = null

    private class Frame(val size: Size, val path: Path) {
        val outline = Outline.Generic(path.asComposePath())
        var contour: Contour? = null
    }

    internal class Contour(val scale: Float, val points: FloatArray, val orientation: Float)

    private fun frame(size: Size): Frame {
        cached?.let { if (it.size == size) return it }
        val outset = ((size.height - base.height()) / 2f).coerceAtLeast(0f)
        val path = Path()
        if (outset > 0.001f) {
            if (InsetIndicatorOutline.expand(path, resting, outset, stroke, paint)) {
                path.offset(outset, outset)
            } else {
                path.setBottomBarSquircle(RectF(0f, 0f, base.width() + 2f * outset,
                    base.height() + 2f * outset), radius + outset, curve)
            }
        } else {
            path.set(resting)
        }
        val w = base.width() + 2f * outset
        val h = base.height() + 2f * outset
        // Only reconcile Android's integer layout rounding; growth itself is a parallel offset.
        if (w != size.width || h != size.height) {
            matrix.setScale(size.width / w, size.height / h)
            path.transform(matrix)
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Trace.isEnabled()) {
            android.os.Trace.setCounter("LiquidTab.ManagerThumb.ExactInset", if (insetBuilt) 1L else 0L)
            android.os.Trace.setCounter("LiquidTab.ManagerThumb.WidthPx", size.width.toLong())
            android.os.Trace.setCounter("LiquidTab.ManagerThumb.HeightPx", size.height.toLong())
            android.os.Trace.setCounter("LiquidTab.ManagerThumb.BaseHeightPx", base.height().toLong())
        }
        return Frame(size, path).also { cached = it }
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        frame(size).outline

    fun copyStretchedOutline(size: Size, sx: Float, sy: Float, out: Path, transform: Matrix) {
        transform.setScale(sx, sy, size.width / 2f, size.height / 2f)
        frame(size).path.transform(transform, out)
    }

    internal fun contour(size: Size, scale: Float): Contour {
        val frame = frame(size)
        frame.contour?.let { if (it.scale == scale) return it }
        val measure = PathMeasure(frame.path, true)
        val length = measure.length
        val point = FloatArray(2)
        val points = FloatArray(128)
        for (i in 0 until 64) {
            measure.getPosTan(length * i / 64f, point, null)
            points[i * 2] = point[0] / scale
            points[i * 2 + 1] = point[1] / scale
        }
        var area = 0f
        for (i in 0 until 64) {
            val next = (i + 1) % 64
            area += points[i * 2] * points[next * 2 + 1] - points[next * 2] * points[i * 2 + 1]
        }
        return Contour(scale, points, if (area >= 0f) 1f else -1f).also { frame.contour = it }
    }
}
