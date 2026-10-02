package io.github.offlineglass.hook.adapters.jd

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.os.SystemClock
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import kotlin.math.min

/** Per-host native slot artwork, labels and unread badges. */
internal class JdArtwork {
    data class CaptureLabels(val labels: List<TextView>, val retryLater: Boolean)

    fun inspectCaptureLabels(source: ViewGroup, hasSnapshot: Boolean): CaptureLabels {
        val labels = bottomLabelTextViews(source)
        return CaptureLabels(labels, !hasSnapshot && labels.size >= 2 && labels.any { it.text.isNullOrBlank() })
    }

    fun labelsHiddenDuringHomeCapture(labels: List<TextView>, selectedIndex: Int): List<Pair<TextView, Int>> =
        if (selectedIndex != 0) emptyList() else labels
            .filter { it.text?.toString()?.trim() == JD_HOME_TAB_LABEL }
            .map { it to it.visibility }
    fun bottomLabelTextViews(source: ViewGroup): List<TextView> {
        val sourceLocation = IntArray(2).also(source::getLocationInWindow)
        val sourceCenterY = sourceLocation[1] + source.height / 2f
        val maxLabelHeight = (source.height * 0.35f).toInt().coerceAtLeast(1)
        val maxLabelWidth = (source.width * 0.45f).toInt().coerceAtLeast(1)
        val labels = mutableListOf<TextView>()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += source to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is TextView && view.width in 1..maxLabelWidth && view.height in 1..maxLabelHeight) {
                val location = IntArray(2).also(view::getLocationInWindow)
                if (location[1] + view.height / 2f > sourceCenterY) labels += view
            }
            if (view is ViewGroup && depth < 16) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return labels
    }
    private data class JdBadgeOverlay(val bitmap: Bitmap, val slotRect: RectF)
    private var navigationSource: ViewGroup? = null
    private var slotCount = 5
    private val jdStaticIcons = arrayOfNulls<Bitmap>(5)
    private val jdBadgeOverlays = Array(5) { mutableListOf<JdBadgeOverlay>() }
    private var jdLastBadgeCaptureAt = 0L
    private var jdHomeDefaultIcon: Bitmap? = null
    private var jdHomeDefaultIconRect: Rect? = null
    private var jdNativeFrameWidth = 0
    private var jdNativeFrameHeight = 0
    private val navigationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val jdIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectedIconFilter = BlendModeColorFilter(0xFFE02E24.toInt(), BlendMode.SRC_IN)
    private val lightIconFilter = BlendModeColorFilter(0xFF424242.toInt(), BlendMode.SRC_IN)
    private val darkIconFilter = BlendModeColorFilter(Color.WHITE, BlendMode.SRC_IN)
    var jdStaticIconsPopulated = false
        private set

    fun prepare(source: ViewGroup?, count: Int) {
        if (jdStaticIconsPopulated && (source !== navigationSource || count != slotCount ||
                source?.width != jdNativeFrameWidth || source.height != jdNativeFrameHeight)) {
            jdStaticIconsPopulated = false
            jdHomeDefaultIcon = null
            jdHomeDefaultIconRect = null
        }
        navigationSource = source
        slotCount = count
    }

    fun accept(candidate: Bitmap, populate: Boolean): Boolean {
        if (!jdStaticIconsPopulated && (!populate || !populateJdStaticIcons(candidate))) return false
        captureJdBadgeOverlays(candidate)
        return true
    }
    fun refreshBadges(candidate: Bitmap) = captureJdBadgeOverlays(candidate)
    private fun captureJdBadgeOverlays(candidate: Bitmap) {

        val source = navigationSource ?: return

        if (source.width <= 0 || source.height <= 0 || candidate.width <= 0 || candidate.height <= 0) return

        val now = android.os.SystemClock.uptimeMillis()

        if (now - jdLastBadgeCaptureAt < JD_BADGE_CAPTURE_INTERVAL_MS) return

        jdLastBadgeCaptureAt = now

        clearJdBadgeOverlays()

        val sourceLocation = IntArray(2).also(source::getLocationInWindow)

        val scaleX = candidate.width.toFloat() / source.width.toFloat()

        val scaleY = candidate.height.toFloat() / source.height.toFloat()

        val stack = ArrayDeque<View>()

        stack += source

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            if (view.javaClass.name == JD_RED_POINT_VIEW_CLASS && view.isShown &&

                view.width > 0 && view.height > 0 && view.alpha > 0f

            ) {

                val location = IntArray(2).also(view::getLocationInWindow)

                val left = (location[0] - sourceLocation[0]) * scaleX

                val top = (location[1] - sourceLocation[1]) * scaleY

                val right = left + view.width * scaleX

                val bottom = top + view.height * scaleY

                val centerX = (left + right) * 0.5f

                val index = (centerX / candidate.width * slotCount).toInt().coerceIn(0, slotCount - 1)

                if (index in JD_BADGE_TAB_INDICES && index < jdBadgeOverlays.size) {

                    val overlay = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

                    Canvas(overlay).also(view::draw)

                    val slotLeft = candidate.width * index.toFloat() / slotCount

                    jdBadgeOverlays[index] += JdBadgeOverlay(

                        overlay,

                        RectF(left - slotLeft, top, right - slotLeft, bottom),

                    )

                }

            }

            if (view is ViewGroup) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index)

            }

        }

    }



    private fun clearJdBadgeOverlays() {

        jdBadgeOverlays.forEach { overlays ->

            overlays.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }

            overlays.clear()

        }

    }



    /** Extract every tab's icon slot from the settled native frame into the

     *  static icon cache. The bitmap is drawn at its native aspect ratio,

     *  scaled by the slot transform factor to match the visual slot size. */


    private fun populateJdStaticIcons(candidate: Bitmap): Boolean {

        if (candidate.width <= 0 || candidate.height <= 0 || slotCount <= 0) return false

        captureJdDefaultHomeIcon()
        // Do not permanently freeze an async/partially laid-out Home slot.
        val homeSlot = buildJdDefaultHomeSlot(candidate) ?: return false

        jdNativeFrameWidth = candidate.width

        jdNativeFrameHeight = candidate.height

        val count = minOf(slotCount, jdStaticIcons.size)

        for (index in 0 until count) {

            val slotLeft = candidate.width * index / slotCount

            val slotRight = candidate.width * (index + 1) / slotCount

            val slotWidth = slotRight - slotLeft

            if (slotWidth <= 0) continue

            jdStaticIcons[index]?.takeUnless(Bitmap::isRecycled)?.recycle()

            jdStaticIcons[index] = Bitmap.createBitmap(

                candidate, slotLeft, 0, slotWidth, candidate.height

            )

        }

        // Rebuild Home as a complete native slot (icon + JD's own TextView),

        // just like the other four cached items. Only its unstable Lottie

        // artwork is replaced by the app's default house drawable.

        homeSlot.let { homeSlot ->

            jdStaticIcons[0]?.takeUnless(Bitmap::isRecycled)?.recycle()

            jdStaticIcons[0] = homeSlot

        }

        jdStaticIconsPopulated = true
        return true

    }



    /**

     * Extract the file manager's main tab row artwork (最近/浏览 tabs plus the

     * standalone circular search key) into the static icon cache. Unlike the

     * generic snapshot path, each element is drawn in isolation, so the pill

     * geometry never distorts the artwork. The multi-select action bar does

     * not use this cache (its source id is split_action_bar).

     */


    private fun buildJdDefaultHomeSlot(candidate: Bitmap): Bitmap? {

        val source = navigationSource ?: return null

        val house = jdHomeDefaultIcon?.takeUnless(Bitmap::isRecycled) ?: return null

        if (slotCount <= 0 || source.width <= 0 || source.height <= 0) return null

        val slotWidth = candidate.width / slotCount

        if (slotWidth <= 0) return null

        val sourceLocation = IntArray(2).also(source::getLocationInWindow)

        val stack = ArrayDeque<View>()

        stack += source

        var iconView: View? = null
        val iconViews = mutableListOf<View>()

        var iconCenterX = Float.MAX_VALUE

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            if (view.javaClass.name == JD_NAVIGATION_ICON_VIEW_CLASS && view.width > 0 && view.height > 0) {
                iconViews += view

                val location = IntArray(2).also(view::getLocationInWindow)

                val centerX = location[0] - sourceLocation[0] + view.width * 0.5f

                if (centerX < iconCenterX) {

                    iconCenterX = centerX

                    iconView = view

                }

            }

            if (view is ViewGroup) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index)

            }

        }

        val homeIconView = iconView ?: return null

        var ancestor: Any? = homeIconView.parent

        var homeButton: View? = null

        while (ancestor is View) {

            if (ancestor.javaClass.name == JD_NAVIGATION_BUTTON_CLASS) {

                homeButton = ancestor

                break

            }

            ancestor = ancestor.parent

        }

        val label = (homeButton?.let { readPrivateField(it, "naviText") } as? TextView)

            ?: (readPrivateField(homeIconView, "mNaviText") as? TextView)

            ?: return null

        val scaleX = candidate.width.toFloat() / source.width.toFloat()

        val scaleY = candidate.height.toFloat() / source.height.toFloat()

        // The selected Home view/lastIconRect belongs to JD's Lottie morph.
        // Use settled peer bounds and fit the house uniformly, never stretch
        // it to that transient rectangle or reuse the label's animated X.
        val peers = iconViews.filter { it !== homeIconView }.mapNotNull { peer ->
            val drawable = (readPrivateField(peer, "defaultDrawable") as? Drawable)
                ?: (readPrivateField(peer, "defaultNormalDrawable") as? Drawable)
            val bounds = (drawable?.let { readPrivateField(it, "lastIconRect") } as? Rect)
                ?.takeIf { it.width() > 0 && it.height() > 0 &&
                    it.left >= 0 && it.top >= 0 && it.right <= peer.width && it.bottom <= peer.height }
                ?: return@mapNotNull null
            val location = IntArray(2).also(peer::getLocationInWindow)
            RectF(0f, (location[1] - sourceLocation[1] + bounds.top).toFloat(),
                bounds.width().toFloat(), (location[1] - sourceLocation[1] + bounds.bottom).toFloat())
        }
        if (peers.size < 2 || label.paint.textSize <= 0f) return null
        fun median(values: List<Float>) = values.sorted()[values.size / 2]
        val iconWidth = median(peers.map { it.width() }) * scaleX
        val iconHeight = median(peers.map { it.height() }) * scaleY
        val iconCenterY = median(peers.map { it.centerY() }) * scaleY
        val fit = min(iconWidth / house.width, iconHeight / house.height)
        val houseWidth = house.width * fit
        val houseHeight = house.height * fit

        val slot = Bitmap.createBitmap(slotWidth, candidate.height, Bitmap.Config.ARGB_8888)

        val slotCanvas = Canvas(slot)

        val houseRect = RectF((slotWidth - houseWidth) / 2f, iconCenterY - houseHeight / 2f,
            (slotWidth + houseWidth) / 2f, iconCenterY + houseHeight / 2f)

        slotCanvas.drawBitmap(house, null, houseRect, navigationPaint)



        val labelLocation = IntArray(2).also(label::getLocationInWindow)

        // JD suppresses the Home TextView's own draw pass while Home is

        // selected. Reuse its exact native paint metrics and baseline, but

        // draw the fixed label directly so the cached slot can never lose it.

        val labelPaint = Paint(label.paint).apply {

            color = Color.WHITE

            alpha = 255

            textAlign = Paint.Align.CENTER

            style = Paint.Style.FILL
            textScaleX = 1f

        }

        val labelCenterX = source.width.toFloat() / slotCount / 2f

        // Home's selected-state TextView is temporarily shifted upward by JD.

        // Use the median baseline of the other native tab labels so all five

        // captions share one visual line without hard-coding a pixel offset.

        val peerBaselines = mutableListOf<Float>()

        val labelStack = ArrayDeque<View>()

        labelStack += source

        while (labelStack.isNotEmpty()) {

            val view = labelStack.removeLast()

            if (view.javaClass.name == JD_NAVIGATION_BUTTON_CLASS) {

                val peer = readPrivateField(view, "naviText") as? TextView

                if (peer != null && peer !== label && peer.width > 0 && peer.height > 0) {

                    val peerLocation = IntArray(2).also(peer::getLocationInWindow)

                    peerBaselines += peerLocation[1] - sourceLocation[1] + peer.baseline.toFloat()

                }

            }

            if (view is ViewGroup) {

                for (index in 0 until view.childCount) labelStack += view.getChildAt(index)

            }

        }

        peerBaselines.sort()

        val labelBaseline = peerBaselines.getOrNull(peerBaselines.size / 2)

            ?: (labelLocation[1] - sourceLocation[1] + label.baseline.toFloat())

        slotCanvas.save()

        slotCanvas.scale(scaleX, scaleY)

        slotCanvas.drawText(JD_HOME_TAB_LABEL, labelCenterX, labelBaseline, labelPaint)

        slotCanvas.restore()

        return slot

    }



    /**

     * Read JD's own non-animated Home drawable from the left-most

     * NavigationIconView. This avoids both hand-drawn artwork and the selected

     * Joy/Lottie frame while keeping the asset matched to the installed JD

     * version and current skin.

     */


    private fun captureJdDefaultHomeIcon() {

        if (jdHomeDefaultIcon?.let { !it.isRecycled } == true) return

        val source = navigationSource ?: return

        val stack = ArrayDeque<View>()

        stack += source

        var homeIconView: View? = null

        var homeCenterX = Float.MAX_VALUE

        val sourceLocation = IntArray(2).also(source::getLocationInWindow)

        while (stack.isNotEmpty()) {

            val view = stack.removeLast()

            if (view.javaClass.name == JD_NAVIGATION_ICON_VIEW_CLASS &&

                view.isAttachedToWindow && view.width > 0 && view.height > 0

            ) {

                val location = IntArray(2).also(view::getLocationInWindow)

                val centerX = location[0] - sourceLocation[0] + view.width * 0.5f

                if (centerX < homeCenterX) {

                    homeCenterX = centerX

                    homeIconView = view

                }

            }

            if (view is ViewGroup) {

                for (index in 0 until view.childCount) stack += view.getChildAt(index)

            }

        }

        val iconView = homeIconView ?: return

        val drawable = (readPrivateField(iconView, "defaultDrawable") as? Drawable)

            ?: (readPrivateField(iconView, "defaultNormalDrawable") as? Drawable)

            ?: return

        jdHomeDefaultIconRect = (readPrivateField(drawable, "lastIconRect") as? Rect)?.let(::Rect)

        val sourceBitmap = readPrivateField(drawable, "bitmap") as? Bitmap

        val copied = sourceBitmap?.takeUnless(Bitmap::isRecycled)?.copy(Bitmap.Config.ARGB_8888, false)

            ?: run {

                val iconWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: iconView.width

                val iconHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: iconView.height

                if (iconWidth <= 0 || iconHeight <= 0) return

                Bitmap.createBitmap(iconWidth, iconHeight, Bitmap.Config.ARGB_8888).also { target ->

                    val oldBounds = Rect(drawable.bounds)

                    drawable.setBounds(0, 0, target.width, target.height)

                    drawable.draw(Canvas(target))

                    drawable.setBounds(oldBounds)

                }

            }

        jdHomeDefaultIcon?.takeUnless(Bitmap::isRecycled)?.recycle()

        jdHomeDefaultIcon = copied

    }



    /** Commit the candidate bitmap as the navigation snapshot. */

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (!jdStaticIconsPopulated || frame.slotCount <= 0 || frame.width <= 0 || frame.height <= 0) return
        if (jdNativeFrameWidth <= 0 || jdNativeFrameHeight <= 0) return
        val itemScale = frame.iconScale * frame.extraScale * JD_STATIC_CONTENT_SCALE
        val contentCenterY = frame.height / 2f + JD_STATIC_CONTENT_OFFSET_Y_DP * frame.density
        for ((visualIndex, index) in frame.enabledIndices.withIndex()) {
            val bitmap = jdStaticIcons.getOrNull(index)?.takeUnless(Bitmap::isRecycled) ?: continue
            val centerX = 4f * frame.density + (visualIndex + 0.5f) * frame.slotWidth
            val selected = index == frame.selectedIndex
            canvas.save()
            canvas.scale(itemScale, itemScale, centerX, contentCenterY)
            jdIconPaint.colorFilter = if (selected) selectedIconFilter
                else if (frame.darkGlass) darkIconFilter else lightIconFilter
            jdIconPaint.alpha = if (selected) 255 else 235
            val bmpW = bitmap.width.toFloat().coerceAtLeast(1f)
            val bmpH = bitmap.height.toFloat().coerceAtLeast(1f)
            val drawScale = min(frame.slotWidth / bmpW, frame.height / bmpH)
            val drawWidth = bmpW * drawScale
            val drawHeight = bmpH * drawScale
            val iconRect = RectF(centerX - drawWidth / 2f, contentCenterY - drawHeight / 2f,
                centerX + drawWidth / 2f, contentCenterY + drawHeight / 2f)
            canvas.drawBitmap(bitmap, null, iconRect, jdIconPaint)
            jdIconPaint.colorFilter = null
            jdIconPaint.alpha = 255
            jdBadgeOverlays.getOrNull(index).orEmpty().forEach { overlay ->
                val destination = RectF(
                    iconRect.left + overlay.slotRect.left * drawScale,
                    iconRect.top + overlay.slotRect.top * drawScale,
                    iconRect.left + overlay.slotRect.right * drawScale,
                    iconRect.top + overlay.slotRect.bottom * drawScale,
                )
                canvas.drawBitmap(overlay.bitmap, null, destination, jdIconPaint)
            }
            canvas.restore()
        }
        jdIconPaint.colorFilter = null
        jdIconPaint.alpha = 255
    }

    fun dispose() {
        jdStaticIcons.forEach { it?.takeUnless(Bitmap::isRecycled)?.recycle() }
        jdStaticIcons.fill(null)
        clearJdBadgeOverlays()
        jdHomeDefaultIcon?.takeUnless(Bitmap::isRecycled)?.recycle()
        jdHomeDefaultIcon = null
        jdHomeDefaultIconRect = null
        jdStaticIconsPopulated = false
        jdNativeFrameWidth = 0
        jdNativeFrameHeight = 0
        jdLastBadgeCaptureAt = 0L
        navigationSource = null
    }

    private fun readPrivateField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) return runCatching {
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()
            type = type.superclass
        }
        return null
    }

    private companion object {
        const val JD_BADGE_CAPTURE_INTERVAL_MS = 250L
        val JD_BADGE_TAB_INDICES = setOf(2, 3, 4)
        const val JD_RED_POINT_VIEW_CLASS =
            "com.jingdong.common.unification.navigationbar.newbar.RedPointView"
        const val JD_NAVIGATION_ICON_VIEW_CLASS =
            "com.jingdong.common.unification.navigationbar.newbar.NavigationIconView"
        const val JD_NAVIGATION_BUTTON_CLASS =
            "com.jingdong.common.unification.navigationbar.newbar.NavigationButton"
        const val JD_HOME_TAB_LABEL = "首页"
        const val JD_STATIC_CONTENT_SCALE = 1.16f
        const val JD_STATIC_CONTENT_OFFSET_Y_DP = -4f
    }
}

