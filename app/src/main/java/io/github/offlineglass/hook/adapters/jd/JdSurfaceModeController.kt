package io.github.offlineglass.hook.adapters.jd

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.Surface
import android.view.SurfaceView
import android.view.View

/** Keeps JD's transparent compositor layer attached when falling back to direct glass. */
internal object JdSurfaceModeController {
    /** Returns false when the already-cleared direct state needs no further work. */
    fun enterDirectMode(surface: SurfaceView, cache: JdOpticalCache, pipelineActive: Boolean): Boolean {
        if (cache.directBackdropActive && !pipelineActive) return false
        cache.directBackdropActive = true
        cache.surfaceFrameReady = false
        surface.visibility = View.VISIBLE
        surface.holder.surface.takeIf(Surface::isValid)?.let {
            runCatching {
                val canvas: Canvas = surface.holder.lockCanvas()
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                surface.holder.unlockCanvasAndPost(canvas)
            }
        }
        return true
    }
}
