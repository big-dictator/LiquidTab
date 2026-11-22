package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import io.github.offlineglass.hook.setBottomBarSquircle

/** Use the shared smooth curve with tangent extents aligned to the frame. */
internal object AppleMusicArtistCorners {
    // Leave room for the tangent transitions on the short sides of the play capsule.
    fun albumPlayRadius(height: Float) = height * .42f

    fun artwork(width: Float, height: Float, outerRadius: Float, insetX: Float, insetY: Float): Path {
        require(insetX in 0f..<outerRadius && insetY in 0f..<outerRadius)
        // A fully smoothed corner reaches the straight edge at 2R, not R.
        val rx = outerRadius - insetX / 2f
        val ry = outerRadius - insetY / 2f
        val scale = ry / rx
        return Path().apply {
            setBottomBarSquircle(RectF(0f, 0f, width, height / scale), rx, -1f)
            transform(Matrix().apply { setScale(1f, scale) })
        }
    }
}
