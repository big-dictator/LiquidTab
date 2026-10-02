package io.github.offlineglass.hook.adapters.jd

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/** Restricts JD's passive Surface readback to the bottom strip used by the glass backdrop. */
internal object JdCaptureGeometry {
    data class Frame(
        val sourceRect: Rect,
        val targetWidth: Int,
        val targetHeight: Int,
        val barSource: Rect,
    )

    fun calculate(
        rootWidth: Int,
        rootHeight: Int,
        localLeft: Int,
        localTop: Int,
        hostWidth: Int,
        hostHeight: Int,
        padding: Int,
    ): Frame? {
        // Preserve the same pixel density and optical margin, but do not copy
        // the unused navigation inset and full-window side strips.
        val source = Rect(
            (localLeft - padding).coerceAtLeast(0),
            (localTop - padding).coerceAtLeast(0),
            (localLeft + hostWidth + padding).coerceAtMost(rootWidth),
            (localTop + hostHeight + padding).coerceAtMost(rootHeight),
        )
        if (source.width() <= 0 || source.height() <= 0) return null
        val targetWidth = max(1, (source.width() / CAPTURE_DOWNSCALE).roundToInt())
        val targetHeight = max(1, (source.height() / CAPTURE_DOWNSCALE).roundToInt())
        val scaleX = targetWidth.toFloat() / source.width()
        val scaleY = targetHeight.toFloat() / source.height()
        val left = ((localLeft - padding - source.left) * scaleX).roundToInt().coerceIn(0, targetWidth - 1)
        val top = ((localTop - padding - source.top) * scaleY).roundToInt().coerceIn(0, targetHeight - 1)
        val right = ((localLeft + hostWidth + padding - source.left) * scaleX)
            .roundToInt().coerceIn(left + 1, targetWidth)
        val bottom = ((localTop + hostHeight + padding - source.top) * scaleY)
            .roundToInt().coerceIn(top + 1, targetHeight)
        return Frame(source, targetWidth, targetHeight, Rect(left, top, right, bottom))
    }

    private const val CAPTURE_DOWNSCALE = 2f
}
