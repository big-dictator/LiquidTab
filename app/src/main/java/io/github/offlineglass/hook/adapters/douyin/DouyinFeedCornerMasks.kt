package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import java.util.WeakHashMap

/** The two feed corner images created by Douyin's ContainerCornerComponent. */
internal class DouyinFeedCornerMasks {
    private val originalImageAlphas = WeakHashMap<ImageView, Int>()
    val suppressedCount: Int get() = originalImageAlphas.size

    fun suppressAbove(bottomSpace: View) {
        if (bottomSpace.id == View.NO_ID) return
        val parent = bottomSpace.parent as? ViewGroup ?: return
        if (parent.javaClass.simpleName != "DisallowInterceptRelativeLayout") return
        val maxSize = (24f * parent.resources.displayMetrics.density).toInt()
        for (i in 0 until parent.childCount) {
            val image = parent.getChildAt(i) as? ImageView ?: continue
            // Decompiled createCornerView creates an anonymous square DuxImageView
            // with ABOVE=bottom_space. The right one also uses ALIGN_PARENT_END.
            // Top corners use BELOW a different anchor, so they cannot match.
            if (image.id != View.NO_ID || image.javaClass.simpleName != "DuxImageView") continue
            val params = image.layoutParams as? RelativeLayout.LayoutParams ?: continue
            if (params.getRule(RelativeLayout.ABOVE) != bottomSpace.id ||
                params.width !in 1..maxSize || params.height != params.width) continue
            originalImageAlphas.putIfAbsent(image, image.imageAlpha)
            // Only the decorative pixels are hidden. Native visibility updates,
            // feed geometry, controls, and navigation discovery remain intact.
            if (image.imageAlpha != 0) image.imageAlpha = 0
        }
    }

    fun dispose() {
        originalImageAlphas.forEach { (image, alpha) ->
            if (image.imageAlpha == 0) image.imageAlpha = alpha
        }
        originalImageAlphas.clear()
    }
}
