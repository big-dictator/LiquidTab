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
import io.github.offlineglass.hook.adapters.meituan.MeituanTakeoutContract.Role

/** The promotion and iv_top_arc are separate native ImageViews, not one asset. */
internal class MeituanTakeoutNavigationState(
    private val pageHost: GlassHostLayout? = null,
) : AppNavigationState {
    private val orderViewport = MeituanTakeoutOrderViewport()
    private val profileSafety = MeituanTakeoutProfileSafety()
    private var wasOnProfile = false
    private var nativeBinding: MeituanTakeoutBinding? = null
    private var snapshotBinding: MeituanTakeoutBinding? = null
    private var capturedPromotionIndex = -1
    private var capturedPromotionAvailable = false
    private var promotionArtworkReady = false
    override fun isNavigationEntryAvailable(index: Int): Boolean? {
        val graph = nativeBinding
        val campaignIndex = graph?.index(Role.PROMOTION) ?: capturedPromotionIndex
        if (index != campaignIndex || campaignIndex < 0) return null
        // While rebinding, retain committed visuals; stale input is separately
        // refused. A withdrawn live campaign loses its hit target immediately.
        return capturedPromotionAvailable && (graph?.slots?.getOrNull(index)?.activePromotion ?: true)
    }
    private val chrome = MeituanTakeoutChrome()
    private var ownsVisuals = false
    private fun binding(source: ViewGroup?): MeituanTakeoutBinding? {
        val next = nativeBinding?.takeIf { it.row === source && it.valid() }
            ?: MeituanTakeoutBinding.forRow(source)
        if (next !== nativeBinding) {
            orderViewport.release(); profileSafety.release()
            nativeBinding?.release(); nativeBinding = next
        }
        if (next != null && pageHost?.config?.enabled != false) ownsVisuals = true
        return next
    }
    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) { binding(source) }
    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? = binding(source)?.selected()
    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val graph = binding(source) ?: return false
        if (snapshotBinding !== graph) return false
        val cell = graph.slots.getOrNull(index) ?: return false
        if (cell.role == Role.PROMOTION && !cell.activePromotion) {
            graph.report("native campaign delivery absent; refusing hidden uninitialized web route")
            return false
        }
        val button = cell.button()?.takeIf { it.isAttachedToWindow && it.isEnabled } ?: return false
        val dispatched = button.performClick()
        if (cell.role == Role.PROMOTION) runCatching {
            java.io.File(host.context.getExternalFilesDir(null), "liquidtab-takeout-promotion-tap.txt")
                .writeText("pid=${android.os.Process.myPid()} dispatched=$dispatched nativeSelected=${graph.selected()}\n")
        }
        return dispatched
    }
    override fun allowsHostTouch(): Boolean = nativeBinding?.valid() == true && snapshotBinding === nativeBinding
    override fun acceptCapturedNavigation(source: ViewGroup?, target: Bitmap, selectedIndex: Int): Boolean {
        val graph = binding(source) ?: return false
        val campaignIndex = graph.index(Role.PROMOTION)
        val campaign = graph.slots.getOrNull(campaignIndex)
        if (campaign?.activePromotion == true && !promotionArtworkReady) {
            graph.report("promotion artwork not ready; retaining committed navigation")
            return false
        }
        snapshotBinding = graph
        capturedPromotionIndex = campaignIndex
        capturedPromotionAvailable = campaign?.activePromotion == true && promotionArtworkReady
        graph.describeBoundNavigation(pageHost)
        return true
    }
    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int) = ownsVisuals
    override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = ownsVisuals
    override fun skipNavigationSnapshotCapture(source: ViewGroup, snapshot: Bitmap?, touching: Boolean,
        visualAnimating: Boolean, slotCount: Int, now: Long): Boolean? = if (binding(source) == null) true else null
    private val ordinaryGroupCenters = FloatArray(5) { Float.NaN }

    override fun sourceContentOffsetY(
        index: Int, selectedIndex: Int, hostHeight: Int,
        transformDy: Float, transformScale: Float,
    ): Float? {
        if (index == nativeBinding?.index(Role.PROMOTION)) return null
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
        val graph = binding(source) ?: return false
        val transforms = mutableListOf<IconTransform>()
        val clipping = linkedMapOf<ViewGroup, Pair<Boolean, Boolean>>()
        val sourceAlpha = source.alpha
        try {
            for (cell in graph.slots.filter { it.role != Role.PROMOTION }) {
                val icon = cell.icon ?: continue
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
            // A hidden campaign makes the native LinearLayout redistribute the
            // remaining four children. Project each verified native cell into
            // its own stable slot instead of copying that compacted layout.
            for ((index, cell) in graph.slots.withIndex()) {
                if (cell.role == Role.PROMOTION || cell.root.visibility != View.VISIBLE ||
                    cell.root.width <= 0 || cell.root.height <= 0) continue
                val save = canvas.save()
                try {
                    val center = (index + .5f) * target.width / graph.slots.size
                    canvas.translate(center - cell.root.width / 2f, cell.root.top.toFloat())
                    cell.root.draw(canvas)
                } finally { canvas.restoreToCount(save) }
            }
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
        val graph = binding(source) ?: return
        ordinaryGroupCenters.fill(Float.NaN)
        for ((index, cell) in graph.slots.withIndex()) {
            if (cell.role == Role.PROMOTION) continue
            val group = cell.normal
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
        val graph = binding(source)
        val onProfile = enabled && graph?.slots?.getOrNull(index)?.role == Role.PROFILE
        if (onProfile != wasOnProfile) {
            (host as? GlassHostLayout)?.onAdapterScrollHideTimerChanged(false, 0L)
            wasOnProfile = onProfile
        }
        if (!enabled) {
            MeituanTakeoutOwnership.restoreWhenDisabled()
            chrome.release(); orderViewport.release(); profileSafety.release(); ownsVisuals = false
            return
        }
        if (graph == null) { chrome.maintain(); return }
        chrome.update(graph)
        val page = if (graph.slots.getOrNull(index)?.role in setOf(Role.ORDERS, Role.PROFILE)) graph.page(index) else null
        orderViewport.update(graph, page, graph.slots.getOrNull(index)?.role == Role.ORDERS)
        profileSafety.update(host, page?.profileScroll, onProfile)
    }

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean? {
        if (!host.config.enabled) return false
        val graph = binding(host.adapterNavigationSource) ?: return ownsVisuals
        return if (graph.slots.getOrNull(graph.selected() ?: -1)?.role == Role.PROFILE) true else null
    }
    override fun forceNavigationBarImmersed(host: GlassHostLayout): Boolean? =
        if (!host.config.enabled) false else null

    override fun dispose() {
        val disabled = pageHost?.config?.enabled == false
        if (disabled) MeituanTakeoutOwnership.restoreWhenDisabled()
        chrome.release(); nativeBinding?.release(); nativeBinding = null; snapshotBinding = null
        orderViewport.release(); profileSafety.release()
    }
    override fun onHostDetached() = dispose()

    override fun transformNavigationSnapshot(
        source: ViewGroup, snapshot: Bitmap, slotCount: Int,
    ): Bitmap? {
        // Only replace the known five-tab promotion slot. Other native layouts
        // and unloaded/hidden promotion artwork retain their original snapshot.
        val graph = binding(source) ?: return null
        promotionArtworkReady = false
        val index = graph.index(Role.PROMOTION)
        val cell = graph.slots.getOrNull(index) ?: return null
        val promotion = cell.promotion ?: return null
        if (!cell.activePromotion || promotion.drawable == null ||
            promotion.width <= 0 || promotion.height <= 0) return null

        val artwork = Bitmap.createBitmap(promotion.width, promotion.height, Bitmap.Config.ARGB_8888)
        try {
            // Draw just the native promotion view: neither the arc sibling nor
            // the enclosing CardView background belongs to the activity artwork.
            promotion.draw(Canvas(artwork))
            val bounds = visibleBounds(artwork) ?: return null
            val slotLeft = index * snapshot.width / slotCount
            val slotRight = (index + 1) * snapshot.width / slotCount
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
                promotionArtworkReady = true
            } finally {
                canvas.restoreToCount(save)
            }
        } finally {
            artwork.recycle()
        }
        return snapshot
    }

}
