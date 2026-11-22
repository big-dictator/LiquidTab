package io.github.offlineglass.hook.adapters.mi_market

import android.graphics.Bitmap
import android.graphics.RenderEffect

/** Reuse blur only while its sampled input is unchanged, never across readbacks. */
internal class MiMarketBlurFrames {
    private var generation = 0L
    private data class Input(val generation: Long, val width: Int, val height: Int, val effect: RenderEffect?)
    private var pending: Input? = null
    private var recorded: Input? = null
    fun captured() { generation++ }
    fun needsRecord(prepared: Boolean, bitmap: Bitmap?, width: Int, height: Int, effect: RenderEffect?): Boolean {
        pending = Input(generation, width, height, effect)
        return !prepared || pending != recorded
    }
    fun recorded() { recorded = pending }
}
