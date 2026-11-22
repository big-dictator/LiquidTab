package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Canvas
import android.graphics.Path
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame

/** Bilibili-family artwork split by the live lens outline, independent of page state. */
internal object BilibiliOpticalForeground {
    fun draw(canvas: Canvas, frame: AdapterNavigationFrame, lens: Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || lens.isEmpty) {
            drawSource()
            return true
        }
        val outside = canvas.save()
        canvas.clipOutPath(lens)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(lens)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }
}
