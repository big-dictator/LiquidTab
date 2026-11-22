package io.github.offlineglass.hook.adapters.pdd

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.os.SystemClock
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pinduoduo's five-slot artwork, badge separation and native shell ownership. */
internal class PddNavigationState : AppNavigationState {
    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val staticItems = arrayOfNulls<Bitmap>(5)
    private val badgeItems = arrayOfNulls<Bitmap>(5)
    private val iconBounds = arrayOfNulls<Rect>(5)
    private var artworkPixels = IntArray(0)
    private var activeHost: GlassHostLayout? = null
    private var selectedIndex = 0
    private var shell: NativeShell? = null
    private var lastShellProbe = 0L
    private var reportedShellMismatch = false
    private var mainContent: View? = null
    private var originalMainBottomMargin: Int? = null
    private var originalMainHeight: Int? = null
    private val promotionBackdrop = PddPromotionBackdrop()
    private val transitionOptics = PddTransitionOptics()
    private var promotionHandoffUntil = 0L
    private var leavingPromotion = false
    private val topActionBaseTranslations = java.util.WeakHashMap<View, Float>()
    private var topActionTarget: View? = null
    private var lastTopActionProbe = 0L

    override fun onHostFrame(host: View) {
        val glassHost = host as? GlassHostLayout ?: return
        activeHost = glassHost
        val next = glassHost.adapterResolvedSelection() ?: selectedIndex
        if (next != selectedIndex && next != PDD_PROMOTION_INDEX) transitionOptics.clear()
        if (next != selectedIndex && (next == PDD_PROMOTION_INDEX || selectedIndex == PDD_PROMOTION_INDEX)) {
            leavingPromotion = next != PDD_PROMOTION_INDEX
            val handoffMillis = if (leavingPromotion) PROMOTION_HANDOFF_MS else PROMOTION_ENTRY_MS
            val deadline = SystemClock.uptimeMillis() + handoffMillis
            promotionHandoffUntil = deadline
            glassHost.postDelayed({
                if (promotionHandoffUntil == deadline && glassHost.isAttachedToWindow) {
                    glassHost.postInvalidateOnAnimation()
                }
            }, handoffMillis)
        }
        selectedIndex = next
        suppressNativeChrome(glassHost.adapterNavigationSource)
        if (selectedIndex == PDD_PROMOTION_INDEX) {
            promotionBackdrop.update(glassHost, mainContent)
            if (promotionBackdrop.scene() != null) transitionOptics.clear()
        } else if (leavingPromotion && !promotionBackdrop.isSourceVisible() &&
            SystemClock.uptimeMillis() >= promotionHandoffUntil
        ) {
            leavingPromotion = false
            promotionBackdrop.release()
        }
        if (selectedIndex != PDD_PROMOTION_INDEX && !leavingPromotion) {
            transitionOptics.prepare(glassHost) { selectedIndex == next && !leavingPromotion }
        }
    }

    override val allowsSoftwareBackdropCapture: Boolean
        get() = selectedIndex != PDD_PROMOTION_INDEX && !leavingPromotion

    override fun retainBackdropDuringTransition(now: Long): Boolean =
        now < promotionHandoffUntil && when {
            selectedIndex == PDD_PROMOTION_INDEX ->
                promotionBackdrop.scene() == null && transitionStrip() == null
            leavingPromotion -> promotionBackdrop.isSourceVisible()
            else -> false
        }

    override val suppliesRetainedNavigationBackdrop: Boolean
        get() = selectedIndex == PDD_PROMOTION_INDEX ||
            (leavingPromotion && promotionBackdrop.isSourceVisible())

    override fun retainedNavigationBackdrop() = promotionBackdrop.retainedScene() ?: transitionStrip()

    private fun transitionStrip() = activeHost?.takeIf {
        selectedIndex == PDD_PROMOTION_INDEX && SystemClock.uptimeMillis() < promotionHandoffUntil
    }?.let(transitionOptics::backdrop)

    override fun onLiveBackdropRecorded(selectedIndex: Int) {
        if (!leavingPromotion || selectedIndex == PDD_PROMOTION_INDEX ||
            promotionBackdrop.isSourceVisible()) return
        // The host has replaced its scene with the destination. Only now
        // release the old WebView layer without clearing the destination.
        leavingPromotion = false
        promotionHandoffUntil = 0L
        promotionBackdrop.release(sceneReplaced = true)
    }

    override fun onHostDetached() {
        transitionOptics.clear()
        leavingPromotion = false
        promotionHandoffUntil = 0L
        promotionBackdrop.release()
        restoreContentInset()
    }

    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean =
        selectedIndex == HOME_INDEX

    override fun suppressNativeChrome(source: ViewGroup?) {
        val host = activeHost ?: return
        if (source !== host.adapterNavigationSource) return
        val existing = shell
        val binding = if (existing != null && existing.matches(source)) existing else {
            val now = SystemClock.uptimeMillis()
            if (now - lastShellProbe < SHELL_PROBE_MS) return
            lastShellProbe = now
            restoreContentInset()
            bindNativeShell(source).also {
                shell = it
                mainContent = it?.content
                if (existing?.content !== it?.content) host.invalidateOpticalBackdropForAdapter()
                if (it == null && !reportedShellMismatch && selectedIndex == PDD_PROMOTION_INDEX) {
                    reportedShellMismatch = true
                    val frame = source?.parent as? ViewGroup
                    android.util.Log.w("LiquidTabPdd", "Promotion shell did not match: tabs=${source?.childCount}" +
                        " frame=${frame?.javaClass?.simpleName} children=" +
                        (0 until (frame?.childCount ?: 0)).joinToString { index ->
                            val child = frame?.getChildAt(index)
                            val params = child?.layoutParams as? RelativeLayout.LayoutParams
                            "${child?.javaClass?.simpleName}(w=${params?.width},h=${params?.height}," +
                                "margin=${params?.bottomMargin},rules=${params?.rules?.contentToString()})"
                        })
                }
            }
        } ?: return
        if (binding.tabs.alpha != 0f) binding.tabs.alpha = 0f
        if (binding.tabs.backgroundTintList != null) binding.tabs.backgroundTintList = null
        if (binding.tabs.background != null) binding.tabs.background = null
        if (binding.tabs.foreground != null) binding.tabs.foreground = null
        binding.divider?.let { if (it.alpha != 0f) it.alpha = 0f }

        // The native tab row is hidden for the entire lifetime of this binding.
        // Its reserved space would expose the frame background under the floating bar.
        val params = binding.content.layoutParams as? RelativeLayout.LayoutParams ?: return
        val needsHeight = params.height != ViewGroup.LayoutParams.MATCH_PARENT
        if (params.bottomMargin > 0 || needsHeight) {
            if (params.bottomMargin > 0) {
                originalMainBottomMargin = params.bottomMargin
                params.bottomMargin = 0
            }
            if (needsHeight) {
                originalMainHeight = params.height
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            binding.content.layoutParams = params
        }
    }

    private fun restoreContentInset() {
        val margin = originalMainBottomMargin
        val height = originalMainHeight
        if (margin == null && height == null) return
        originalMainBottomMargin = null
        originalMainHeight = null
        val content = mainContent ?: return
        val params = content.layoutParams as? RelativeLayout.LayoutParams ?: return
        if (margin != null && params.bottomMargin == 0) params.bottomMargin = margin
        if (height != null && params.height == ViewGroup.LayoutParams.MATCH_PARENT) params.height = height
        content.layoutParams = params
    }

    private data class NativeShell(
        val frame: RelativeLayout,
        val tabs: ViewGroup,
        val content: FrameLayout,
        val divider: View?,
        val childCount: Int,
    ) {
        fun matches(source: ViewGroup?): Boolean =
            source === tabs && tabs.parent === frame && frame.childCount == childCount &&
                content.parent === frame && (divider == null || divider.parent === frame)
    }

    private fun bindNativeShell(source: ViewGroup?): NativeShell? {
        val tabs = source as? LinearLayout ?: return null
        val frame = tabs.parent as? RelativeLayout ?: return null
        val tabParams = tabs.layoutParams as? RelativeLayout.LayoutParams ?: return null
        if (tabs.orientation != LinearLayout.HORIZONTAL || tabs.childCount != 5 ||
            tabParams.width != ViewGroup.LayoutParams.MATCH_PARENT || tabParams.height <= 0 ||
            tabParams.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) == 0
        ) return null

        var divider: View? = null
        var content: FrameLayout? = null
        for (index in 0 until frame.childCount) {
            val child = frame.getChildAt(index)
            val params = child.layoutParams as? RelativeLayout.LayoutParams ?: continue
            if (params.width != ViewGroup.LayoutParams.MATCH_PARENT) continue
            if (child.javaClass == View::class.java && params.height == 1 &&
                params.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0 &&
                params.bottomMargin in 1..tabParams.height
            ) {
                if (divider != null) return null
                divider = child
            }
            if (child is FrameLayout &&
                (params.height == ViewGroup.LayoutParams.MATCH_PARENT || child.height >= frame.height * .70f) &&
                params.getRule(RelativeLayout.ALIGN_PARENT_TOP) != 0
            ) {
                if (content != null) return null
                content = child
            }
        }
        val scene = content ?: return null
        return NativeShell(frame, tabs, scene, divider, frame.childCount)
    }

    override fun resolveBackdropScene(host: View): View? =
        if (selectedIndex == PDD_PROMOTION_INDEX) promotionBackdrop.scene() else mainContent

    override fun enforceContentClipping() {
        (mainContent as? ViewGroup)?.let { it.clipChildren = true; it.clipToPadding = true }
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        val glassHost = host as? GlassHostLayout ?: return
        if (!glassHost.isAttachedToWindow || glassHost.width <= 0 || glassHost.height <= 0) return
        val root = glassHost.rootView as? ViewGroup ?: return
        val density = glassHost.resources.displayMetrics.density
        val hostTop = IntArray(2).also(glassHost::getLocationOnScreen)[1]
        if (hostTop <= 0) return
        val bottomGap = ((glassHost.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0)
            .coerceAtLeast((10f * density).roundToInt())
        val desiredBottom = hostTop - bottomGap
        val now = SystemClock.uptimeMillis()
        var target = topActionTarget?.takeIf { it.isAttachedToWindow && it.isShown }
        if (target == null && now - lastTopActionProbe >= TOP_ACTION_PROBE_MS) {
            lastTopActionProbe = now
            val minSize = (34f * density).roundToInt(); val maxSize = (112f * density).roundToInt()
            var labelled: View? = null; var fallback: View? = null; var fallbackScore = Float.MAX_VALUE
            val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
            while (stack.isNotEmpty()) {
                val (view, depth) = stack.removeLast()
                if (view === glassHost || view === source || !view.isShown || view.alpha <= .02f) continue
                val label = if (view is TextView) view.text?.toString().orEmpty() else view.contentDescription?.toString().orEmpty()
                val idName = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("").lowercase()
                if (label.contains("顶部") || label.contains("回顶部") || idName.contains("back_top") || idName.contains("backtop") || idName.contains("to_top")) {
                    var current: View? = view; var best = view
                    repeat(7) {
                        val candidate = current ?: return@repeat
                        if (candidate.width in minSize..maxSize && candidate.height in minSize..maxSize) {
                            best = candidate; if (candidate.isClickable) labelled = candidate
                        }
                        current = candidate.parent as? View
                    }
                    if (labelled == null) labelled = best
                }
                if (view.width in minSize..maxSize && view.height in minSize..maxSize && view.isClickable) {
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val bottom = location[1] + view.height
                    if (location[0] + view.width * .5f >= root.width * .72f && bottom > desiredBottom && location[1] < hostTop + glassHost.height) {
                        val score = kotlin.math.abs(view.width - view.height) + kotlin.math.abs(bottom - hostTop) * .1f
                        if (score < fallbackScore) { fallbackScore = score; fallback = view }
                    }
                }
                if (view is ViewGroup && depth < 16) for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to depth + 1
            }
            target = labelled ?: fallback
            if (topActionTarget !== target) {
                topActionTarget?.let { old -> topActionBaseTranslations[old]?.let { old.translationY = it } }
                topActionTarget = target
            }
        }
        target?.let { action ->
            val base = topActionBaseTranslations.getOrPut(action) { action.translationY }
            val location = IntArray(2).also(action::getLocationOnScreen)
            val currentBottom = location[1] + action.height
            action.translationY = base - (currentBottom - desiredBottom).coerceAtLeast(0)
        }
    }

    override fun captureNavigation(source: ViewGroup, target: Bitmap): Boolean {
        val container = findSlotContainer(source) ?: return false
        for (index in 0 until 5) {
            val child = container.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) continue
            val raw = Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888)
            Canvas(raw).apply {
                drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
                child.draw(this)
            }
            val bounds = opaqueBounds(raw) ?: run { raw.recycle(); continue }
            val cropped = Bitmap.createBitmap(raw, bounds.left, bounds.top, bounds.width(), bounds.height())
            val badge = if (index == CHAT_INDEX) extractBadge(cropped) else null
            val item = if (badge != null) removeBadge(cropped, badge).also { cropped.recycle() } else cropped
            staticItems[index]?.takeUnless(Bitmap::isRecycled)?.recycle()
            badgeItems[index]?.takeUnless(Bitmap::isRecycled)?.recycle()
            staticItems[index] = item
            badgeItems[index] = badge
            iconBounds[index] = findIconBand(item, source.resources.displayMetrics.density)
            raw.recycle()
        }
        Canvas(target).drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
        return true
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (frame.slotCount != 5 || frame.width <= 0 || frame.height <= 0) return
        val maxWidth = frame.slotWidth * .86f
        val maxHeight = 49f * frame.density
        val color = if (frame.darkGlass) Color.rgb(238, 238, 242) else Color.rgb(38, 38, 40)
        val horizontalPadding = 4f * frame.density
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val bitmap = staticItems[index]?.takeUnless(Bitmap::isRecycled) ?: return@forEachIndexed
            val scale = min(maxWidth / bitmap.width, maxHeight / bitmap.height) *
                frame.iconScale * CONTENT_SCALE * frame.extraScale
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val centerX = horizontalPadding + (visualIndex + .5f) * frame.slotWidth
            val icon = iconBounds[index].takeIf { frame.iconOnly }
            val centerY = frame.height / 2f + (icon?.let { bitmap.height / 2f - it.exactCenterY() } ?: 0f) * scale
            val destination = RectF(centerX - drawWidth / 2f, centerY - drawHeight / 2f,
                centerX + drawWidth / 2f, centerY + drawHeight / 2f)
            itemPaint.colorFilter = if (index == frame.selectedIndex) null
                else BlendModeColorFilter(color, BlendMode.SRC_IN)
            itemPaint.alpha = if (index == frame.selectedIndex) 255 else 248
            if (icon == null) {
                canvas.drawBitmap(bitmap, null, destination, itemPaint)
            } else {
                val iconDestination = RectF(destination.left + icon.left * scale,
                    destination.top + icon.top * scale, destination.left + icon.right * scale,
                    destination.top + icon.bottom * scale)
                canvas.drawBitmap(bitmap, icon, iconDestination, itemPaint)
            }
            badgeItems[index]?.takeUnless(Bitmap::isRecycled)?.let {
                itemPaint.colorFilter = null
                itemPaint.alpha = 255
                canvas.drawBitmap(it, null, destination, itemPaint)
            }
        }
        itemPaint.colorFilter = null
        itemPaint.alpha = 255
    }

    override fun dispose() {
        transitionOptics.clear()
        staticItems.forEachIndexed { index, bitmap -> bitmap?.takeUnless(Bitmap::isRecycled)?.recycle(); staticItems[index] = null }
        badgeItems.forEachIndexed { index, bitmap -> bitmap?.takeUnless(Bitmap::isRecycled)?.recycle(); badgeItems[index] = null }
        iconBounds.fill(null)
        artworkPixels = IntArray(0)
        promotionBackdrop.release()
        activeHost = null
        restoreContentInset()
        shell = null
        topActionBaseTranslations.forEach { (view, base) -> view.translationY = base }
        topActionBaseTranslations.clear(); topActionTarget = null; mainContent = null
    }

    private fun findSlotContainer(root: ViewGroup): ViewGroup? {
        val stack = ArrayDeque<Pair<ViewGroup, Int>>(); stack += root to 0
        while (stack.isNotEmpty()) {
            val (group, depth) = stack.removeLast()
            if (group.childCount == 5 && (0 until 5).all { group.getChildAt(it).width > 0 && group.getChildAt(it).height > 0 }) return group
            if (depth < 5) for (index in 0 until group.childCount) (group.getChildAt(index) as? ViewGroup)?.let { stack += it to depth + 1 }
        }
        return null
    }

    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        pixels.forEachIndexed { i, color -> if (Color.alpha(color) > 8) {
            val x = i % bitmap.width; val y = i / bitmap.width
            left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
        } }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else null
    }

    /** Resolve the two artwork bands in the already-bound native slot, after
     * badge separation. Reject ambiguous or single-band images unchanged. */
    private fun findIconBand(item: Bitmap, density: Float): Rect? {
        val w = item.width
        val h = item.height
        if (artworkPixels.size < w * h) artworkPixels = IntArray(w * h)
        item.getPixels(artworkPixels, 0, w, 0, 0, w, h)
        val split = PddTabArtworkBands.iconEnd(artworkPixels, w, h, density)
        if (split <= 0) return null
        var left = w; var right = -1; var top = split; var bottom = -1
        for (y in 0 until split) for (x in 0 until w) {
            if (Color.alpha(artworkPixels[y * w + x]) <= 24) continue
            left = min(left, x); right = max(right, x)
            top = min(top, y); bottom = max(bottom, y)
        }
        return if (right >= left && bottom >= top) Rect(left, top, right + 1, bottom + 1) else null
    }

    private fun extractBadge(item: Bitmap): Bitmap? {
        val scanBottom = (item.height * .62f).roundToInt().coerceIn(1, item.height)
        val pixels = IntArray(item.width * scanBottom)
        item.getPixels(pixels, 0, item.width, 0, 0, item.width, scanBottom)
        var left = item.width; var top = scanBottom; var right = -1; var bottom = -1
        for (y in 0 until scanBottom) for (x in 0 until item.width) {
            val c = pixels[y * item.width + x]; val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
            if (Color.alpha(c) >= 80 && r >= 175 && r > g * 1.28f && r > b * 1.20f) {
                left = min(left, x); top = min(top, y); right = max(right, x); bottom = max(bottom, y)
            }
        }
        if (right < left || bottom < top) return null
        val rect = Rect((left - 1).coerceAtLeast(0), (top - 1).coerceAtLeast(0),
            (right + 2).coerceAtMost(item.width), (bottom + 2).coerceAtMost(item.height))
        return Bitmap.createBitmap(item.width, item.height, Bitmap.Config.ARGB_8888).also { badge ->
            Canvas(badge).apply {
                clipPath(Path().apply { addRoundRect(RectF(rect), rect.height() * .5f, rect.height() * .5f, Path.Direction.CW) })
                drawBitmap(item, 0f, 0f, null)
            }
        }
    }

    private fun removeBadge(item: Bitmap, badge: Bitmap): Bitmap {
        val result = item.copy(Bitmap.Config.ARGB_8888, true)
        val items = IntArray(item.width * item.height); val badges = IntArray(items.size)
        result.getPixels(items, 0, item.width, 0, 0, item.width, item.height)
        badge.getPixels(badges, 0, item.width, 0, 0, item.width, item.height)
        for (index in items.indices) if (Color.alpha(badges[index]) > 0) items[index] = Color.TRANSPARENT
        result.setPixels(items, 0, item.width, 0, 0, item.width, item.height)
        return result
    }

    private companion object {
        const val HOME_INDEX = 0
        const val CHAT_INDEX = 3
        const val CONTENT_SCALE = .80f
        const val TOP_ACTION_PROBE_MS = 120L
        const val SHELL_PROBE_MS = 160L
        const val PROMOTION_HANDOFF_MS = 420L
        const val PROMOTION_ENTRY_MS = 1_200L
    }
}
