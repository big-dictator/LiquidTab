package io.github.offlineglass.hook.adapters.meituan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.GlassHostLayout

/** The promotion and iv_top_arc are separate native ImageViews, not one asset. */
internal class MeituanTakeoutNavigationState : AppNavigationState {
    private val orderViewport = MeituanTakeoutOrderViewport()
    private val profileSafety = MeituanTakeoutProfileSafety()
    private var wasOnProfile = false
    private val ordinaryGroupCenters = FloatArray(5) { Float.NaN }

    override fun sourceContentOffsetY(
        index: Int, selectedIndex: Int, hostHeight: Int,
        transformDy: Float, transformScale: Float,
    ): Float? {
        if (index == 2) return null
        val center = ordinaryGroupCenters.getOrNull(index)?.takeIf { it.isFinite() } ?: return null
        return hostHeight / 2f - transformDy - center * transformScale
    }

    private data class IconTransform(
        val view: ImageView, val scaleX: Float, val scaleY: Float,
        val pivotX: Float, val pivotY: Float, val translationX: Float, val translationY: Float,
    ) {
        fun restore() {
            view.scaleX = scaleX; view.scaleY = scaleY
            view.pivotX = pivotX; view.pivotY = pivotY
            view.translationX = translationX; view.translationY = translationY
        }
    }

    override fun captureNavigation(source: ViewGroup, target: Bitmap): Boolean {
        if (source.childCount != 5) return false
        val id = source.resources.getIdentifier("icon_normal", "id", source.context.packageName)
        if (id == 0) return false
        val transforms = mutableListOf<IconTransform>()
        val clipping = linkedMapOf<ViewGroup, Pair<Boolean, Boolean>>()
        val sourceAlpha = source.alpha
        try {
            for (index in intArrayOf(0, 1, 3, 4)) {
                val icon = source.getChildAt(index).findViewById<ImageView>(id) ?: continue
                if (icon.visibility != View.VISIBLE || icon.drawable == null ||
                    icon.width <= 0 || icon.height <= 0) continue
                val image = Bitmap.createBitmap(icon.width, icon.height, Bitmap.Config.ARGB_8888)
                val bounds = try {
                    icon.draw(Canvas(image))
                    visibleBounds(image)
                } finally { image.recycle() }
                bounds ?: continue
                val saved = IconTransform(icon, icon.scaleX, icon.scaleY, icon.pivotX,
                    icon.pivotY, icon.translationX, icon.translationY)
                transforms += saved
                // Anchor the visible bottom edge so text and the existing gap
                // stay fixed. Preserve any native scale/translation already applied.
                val x = bounds.exactCenterX()
                val y = bounds.bottom.toFloat()
                icon.pivotX = x; icon.pivotY = y
                icon.translationX = saved.translationX + (saved.pivotX - x) * (1f - saved.scaleX)
                icon.translationY = saved.translationY + (saved.pivotY - y) * (1f - saved.scaleY)
                icon.scaleX = saved.scaleX * 1.2f
                icon.scaleY = saved.scaleY * 1.2f
                var parent = icon.parent as? ViewGroup
                while (parent != null) {
                    if (!clipping.containsKey(parent)) {
                        clipping[parent] = parent.clipChildren to parent.clipToPadding
                        parent.clipChildren = false
                        parent.clipToPadding = false
                    }
                    if (parent === source) break
                    parent = parent.parent as? ViewGroup
                }
            }
            val canvas = Canvas(target)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (sourceAlpha < .01f) source.alpha = 1f
            source.draw(canvas)
            measureOrdinaryGroups(source, target)
        } finally {
            source.alpha = sourceAlpha
            transforms.forEach { it.restore() }
            clipping.forEach { (view, saved) ->
                view.clipChildren = saved.first
                view.clipToPadding = saved.second
            }
        }
        return true
    }

    private fun measureOrdinaryGroups(source: ViewGroup, snapshot: Bitmap) {
        val groupId = source.resources.getIdentifier("ll_normal", "id", source.context.packageName)
        ordinaryGroupCenters.fill(Float.NaN)
        if (groupId == 0) return
        for (index in intArrayOf(0, 1, 3, 4)) {
            val group = source.getChildAt(index).findViewById<View>(groupId) ?: continue
            if (group.visibility != View.VISIBLE || group.height <= 0) continue
            val area = Rect(0, 0, group.width, group.height)
            source.offsetDescendantRectToMyCoords(group, area)
            val left = (index * snapshot.width / 5).coerceAtLeast(0)
            val right = ((index + 1) * snapshot.width / 5).coerceAtMost(snapshot.width)
            // Include the enlarged icon above ll_normal, but exclude the native
            // row's selection underline below the text group.
            val bottom = area.bottom.coerceIn(1, snapshot.height)
            val pixels = IntArray((right - left) * bottom)
            snapshot.getPixels(pixels, 0, right - left, left, 0, right - left, bottom)
            var topInk = bottom
            var bottomInk = -1
            for (y in 0 until bottom) for (x in 0 until right - left) {
                if ((pixels[y * (right - left) + x] ushr 24) == 0) continue
                topInk = minOf(topInk, y)
                bottomInk = maxOf(bottomInk, y)
            }
            if (bottomInk >= topInk) ordinaryGroupCenters[index] = (topInk + bottomInk + 1) / 2f
        }
    }

    private fun visibleBounds(image: Bitmap): Rect? {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((pixels[y * image.width + x] ushr 24) == 0) continue
            left = minOf(left, x); top = minOf(top, y)
            right = maxOf(right, x); bottom = maxOf(bottom, y)
        }
        return if (right < left || bottom < top) null else Rect(left, top, right + 1, bottom + 1)
    }

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float, barHeightPx: Int, surfaceColor: () -> Int,
    ) {
        val index = selected()
        val onProfile = enabled && index == 4
        if (onProfile != wasOnProfile) {
            (host as? GlassHostLayout)?.onAdapterScrollHideTimerChanged(false, 0L)
            wasOnProfile = onProfile
        }
        orderViewport.update(source, enabled && index == 3)
        profileSafety.update(host, onProfile)
    }

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean? {
        val index = host.adapterResolvedSelection() ?: host.adapterSelectedIndex
        return if (index == 4) true else null
    }

    override fun dispose() {
        orderViewport.restore()
        profileSafety.restore()
    }

    override fun transformNavigationSnapshot(
        source: ViewGroup, snapshot: Bitmap, slotCount: Int,
    ): Bitmap? {
        // Only replace the known five-tab promotion slot. Other native layouts
        // and unloaded/hidden promotion artwork retain their original snapshot.
        if (slotCount != 5 || source.childCount != 5) return null
        val slot = source.getChildAt(2) as? ViewGroup ?: return null
        val id = source.resources.getIdentifier("iv_promotion", "id", source.context.packageName)
        if (id == 0) return null
        val promotion = slot.findViewById<ImageView>(id) ?: return null
        if (promotion.visibility != View.VISIBLE || promotion.drawable == null ||
            promotion.width <= 0 || promotion.height <= 0) return null

        val artwork = Bitmap.createBitmap(promotion.width, promotion.height, Bitmap.Config.ARGB_8888)
        try {
            // Draw just the native promotion view: neither the arc sibling nor
            // the enclosing CardView background belongs to the activity artwork.
            promotion.draw(Canvas(artwork))
            val bounds = visibleBounds(artwork) ?: return null
            val slotLeft = 2 * snapshot.width / slotCount
            val slotRight = 3 * snapshot.width / slotCount
            val canvas = Canvas(snapshot)
            val save = canvas.save()
            try {
                canvas.clipRect(slotLeft, 0, slotRight, snapshot.height)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                // Keep the existing artwork size and center its visible pixels,
                // excluding transparent ImageView padding from the calculation.
                canvas.drawBitmap(artwork,
                    (slotLeft + slotRight) / 2f - bounds.exactCenterX(),
                    snapshot.height / 2f - bounds.exactCenterY(), null)
            } finally {
                canvas.restoreToCount(save)
            }
        } finally {
            artwork.recycle()
        }
        return snapshot
    }
}
