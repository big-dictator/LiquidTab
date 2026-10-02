package io.github.offlineglass.hook.adapters.jd

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RenderEffect

/** JD-only compositor identity cache; the generic glass renderer stays shared. */
internal class JdOpticalCache {
    @Volatile var captureGeneration = 0L
    var outerRecordedGeneration = -1L
    var indicatorCombinedGeneration = -1L
    var sharedOpticalGeneration = -1L
    @Volatile var surfaceFrameReady = false
    @Volatile var backdropEpoch = 0
    var liveBackdropEpoch = -1
    var liveBackdropTab = -1
    var directBackdropActive = false
    var backdropBarSourceRect: Rect? = null
    var sharedOpticalBackdrop: Bitmap? = null
    var sharedOpticalWidth = 0
    var sharedOpticalHeight = 0
    var sharedOpticalEffect: RenderEffect? = null
    var outerRecordedBackdrop: Bitmap? = null
    var outerRecordedWidth = 0
    var outerRecordedHeight = 0
    var indicatorCombinedBackdrop: Bitmap? = null
    var indicatorCombinedNavigationBitmap: Bitmap? = null
    var indicatorCombinedNavigationSignature = Long.MIN_VALUE
    var indicatorCombinedWidth = 0
    var indicatorCombinedHeight = 0
    var indicatorCombinedDark: Boolean? = null

    fun reset() {
        captureGeneration = 0L
        outerRecordedGeneration = -1L
        indicatorCombinedGeneration = -1L
        sharedOpticalGeneration = -1L
        surfaceFrameReady = false
        backdropEpoch = 0
        liveBackdropEpoch = -1
        liveBackdropTab = -1
        directBackdropActive = false
        backdropBarSourceRect = null
        sharedOpticalBackdrop = null
        sharedOpticalWidth = 0
        sharedOpticalHeight = 0
        sharedOpticalEffect = null
        outerRecordedBackdrop = null
        outerRecordedWidth = 0
        outerRecordedHeight = 0
        indicatorCombinedBackdrop = null
        indicatorCombinedNavigationBitmap = null
        indicatorCombinedNavigationSignature = Long.MIN_VALUE
        indicatorCombinedWidth = 0
        indicatorCombinedHeight = 0
        indicatorCombinedDark = null
    }
}
