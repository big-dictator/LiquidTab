package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Bitmap
import android.graphics.RenderEffect
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout

/** Reuse GPU records only while all sampled inputs and their geometry match. */
internal class YouTubeOpticalCache(
    private val host: GlassHostLayout,
    private val sampler: YouTubeVideoSampler,
) {
    private val location = IntArray(2)
    private class Record {
        var bitmap: Bitmap? = null
        var generation = -1
        var videoEpoch = Int.MIN_VALUE
        var width = 0
        var height = 0
        var left = 0
        var top = 0
        var config: GlassConfig? = null
        fun clear() { bitmap = null; generation = -1; videoEpoch = Int.MIN_VALUE; config = null }
    }
    private val outer = Record()
    private val blur = Record()
    private var blurEffect: RenderEffect? = null

    private fun needs(record: Record, bitmap: Bitmap?, width: Int, height: Int): Boolean {
        val video = sampler.snapshot
        if (video != null) host.getLocationInWindow(location) else location.fill(0)
        return record.bitmap !== bitmap || record.generation != (bitmap?.generationId ?: -1) ||
            record.videoEpoch != (video?.epoch ?: -1) || record.width != width || record.height != height ||
            record.left != location[0] || record.top != location[1] || record.config != host.config
    }

    private fun record(target: Record, bitmap: Bitmap?, width: Int, height: Int) {
        val video = sampler.snapshot
        if (video != null) host.getLocationInWindow(location) else location.fill(0)
        target.bitmap = bitmap
        target.generation = bitmap?.generationId ?: -1
        target.videoEpoch = video?.epoch ?: -1
        target.width = width; target.height = height
        target.left = location[0]; target.top = location[1]
        target.config = host.config
    }

    fun outerNeedsRecord(bitmap: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean): Boolean =
        !hasDisplayList || needs(outer, bitmap, width, height)
    fun recordOuter(bitmap: Bitmap?, width: Int, height: Int) = record(outer, bitmap, width, height)
    fun blurNeedsRecord(prepared: Boolean, bitmap: Bitmap?, width: Int, height: Int, effect: RenderEffect?): Boolean =
        !prepared || blurEffect !== effect || needs(blur, bitmap, width, height)
    fun recordBlur(bitmap: Bitmap?, width: Int, height: Int, effect: RenderEffect?) {
        record(blur, bitmap, width, height)
        blurEffect = effect
    }
    fun clear() { outer.clear(); blur.clear(); blurEffect = null }
}
