package io.github.offlineglass.hook.adapters.zhihu

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState

/** Projects only named native artwork into independent fixed glass slots. */
internal class ZhihuNavigationState(private val host: GlassHostLayout) : AppNavigationState {
    private data class Item(val custom: View, val icon: View?, val animation: View?, val avatar: View?,
        val label: TextView?, val badges: List<View>,
        val idleArtwork: android.graphics.drawable.Drawable?, val selectedArtwork: android.graphics.drawable.Drawable?)
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val selectedState = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_selected)
    private val idleState = intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_selected)
    private var source: ViewGroup? = null
    private var items = emptyList<Item?>()
    private var refreshAt = 0L
    private var reportedLabels = emptyList<String>()
    private var reportingLabels = false
    private var nextLabelProbe = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPosition = IntArray(2)
    private val badgePosition = IntArray(2)
    private var blurredBitmap: android.graphics.Bitmap? = null
    private var blurredGeneration = -1
    private var blurredWidth = 0
    private var blurredHeight = 0
    private var blurredEffect: android.graphics.RenderEffect? = null
    private var blurredConfig: io.github.offlineglass.config.GlassConfig? = null
    override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: android.graphics.Bitmap?,
        width: Int, height: Int, effect: android.graphics.RenderEffect?): Boolean =
        backdrop == null || !prepared || blurredBitmap !== backdrop || blurredGeneration != (backdrop?.generationId ?: -1) ||
            blurredWidth != width || blurredHeight != height || blurredEffect !== effect || blurredConfig != host.config
    override fun onOpticalBlurNodeRecorded(backdrop: android.graphics.Bitmap?, width: Int,
        height: Int, effect: android.graphics.RenderEffect?) {
        blurredBitmap = backdrop
        blurredGeneration = backdrop?.generationId ?: -1
        blurredWidth = width
        blurredHeight = height
        blurredEffect = effect
        blurredConfig = host.config
    }
    override fun onOpticalRendererConfigurationChanged() {
        blurredBitmap = null
        blurredGeneration = -1
        blurredConfig = null
    }
    override fun onHostFrame(host: View) {
        ZhihuKanshanContent.update(this.host)
        reportNativeLabels()
        // Fresco changes the avatar's drawable in place after loading or an
        // account switch. Its own dirty flag must refresh the projected leaf
        // even when the hidden native row cannot change root PixelCopy pixels.
        if (items.any { item -> item != null &&
            (listOfNotNull(item.icon, item.animation, item.avatar) + item.badges)
                .any { it.visibility == View.VISIBLE && it.isDirty } }) host.postInvalidateOnAnimation()
    }

    private fun reportNativeLabels() {
        if (reportingLabels || items.size !in 2..7) return
        val now = SystemClock.uptimeMillis()
        if (now < nextLabelProbe) return
        nextLabelProbe = now + 1_000L
        if (items.any { it == null }) return
        val labels = items.mapIndexed { index, item ->
            item?.label?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: item?.custom?.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: "第 ${index + 1} 个入口（无文字）"
        }
        if (labels == reportedLabels) return
        reportingLabels = true
        labelReporter.execute {
            val success = runCatching {
                host.context.contentResolver.call(
                    io.github.offlineglass.config.ConfigContract.URI,
                    io.github.offlineglass.config.ConfigContract.METHOD_REPORT_NAVIGATION,
                    host.context.packageName,
                    android.os.Bundle().apply {
                        putStringArrayList(io.github.offlineglass.config.ConfigContract.KEY_NAVIGATION_LABELS,
                            java.util.ArrayList(labels))
                    },
                ) != null
            }.getOrDefault(false)
            mainHandler.post {
                if (success) reportedLabels = labels
                else nextLabelProbe = SystemClock.uptimeMillis() + 30_000L
                reportingLabels = false
            }
        }
    }

    companion object {
        private val labelReporter = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "LiquidTab-ZhihuLabels").apply { isDaemon = true }
        }
    }
    override fun pageAllowsNavigation(root: View?): Boolean = ZhihuSettingsVisibility.allowsNavigation(root)
    override val allowsSoftwareBackdropCapture = false
    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        val now = SystemClock.uptimeMillis()
        val count = source?.let(ZhihuAdapter::tabCount) ?: 0
        if (this.source === source && items.size == count && now < refreshAt &&
            items.all { it == null || it.custom.isAttachedToWindow }) return
        this.source = source
        refreshAt = now + 250L
        items = (0 until count).map { index ->
            val tab = source?.let { call(it, "getTabAt", index) } ?: return@map null
            val custom = call(tab, "getCustomView") as? View ?: return@map null
            // The native ImageView already owns the themed state-list and tint.
            // Clone that public drawable rather than reaching into its model.
            val nativeIcon = find(custom, "tab_icon") as? android.widget.ImageView
            val artwork = nativeIcon?.drawable
            val tint = nativeIcon?.imageTintList
            fun copyArtwork(selected: Boolean): android.graphics.drawable.Drawable? =
                artwork?.constantState?.newDrawable(custom.resources)?.mutate()?.apply {
                    if (tint != null) setTintList(tint)
                    state = if (selected) selectedState else idleState
                    jumpToCurrentState()
                }
            Item(custom, find(custom, "tab_icon"), find(custom, "tab_icon_lav"), find(custom, "avatar"),
                find(custom, "tab_title") as? TextView,
                listOfNotNull(find(custom, "tab_badge_with_count"), find(custom, "tab_badge_with_text"),
                    find(custom, "tab_badge_without_count")), copyArtwork(false), copyArtwork(true))
        }
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
        source?.let { call(it, "getSelectedPosition") as? Number }?.toInt()
            ?.takeIf { it in 0 until slotCount }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        source ?: return false
        if (index !in 0 until ZhihuAdapter.tabCount(source)) return false
        val tab = call(source, "getTabAt", index) ?: return false
        // Tab.select invokes the original menu listeners, including action tabs and reselection.
        return runCatching { XposedHelpers.callMethod(tab, "select"); true }.getOrDefault(false)
    }

    override fun onTapSucceeded(requestRefresh: (Long) -> Unit) {
        refreshAt = 0L
        requestRefresh(0L)
        requestRefresh(80L)
    }

    private fun find(root: View, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", "com.zhihu.android")
        return if (id == 0) null else root.findViewById(id)
    }
    private fun call(target: Any, name: String, vararg args: Any): Any? =
        runCatching { XposedHelpers.callMethod(target, name, *args) }.getOrNull()


    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (!frame.hideSelectedForOptics) drawDetachedPublish(canvas, frame)
        for ((visual, index) in frame.enabledIndices.withIndex()) {
            // The lens samples every native slot, including the selected one.
            // Foreground clipping, rather than index omission, prevents duplicates.
            drawSlot(canvas, index, 4f * frame.density + (visual + 0.5f) * frame.slotWidth,
                frame.height / 2f, frame, frame.extraScale)
        }
    }

    private fun drawDetachedPublish(canvas: Canvas, frame: AdapterNavigationFrame) {
        val layout = frame.barLayout?.takeIf { it.actionIndex == 2 } ?: return
        val cx = layout.actionLeft + layout.actionSize / 2f
        val cy = frame.height / 2f
        val half = minOf(10f * frame.density * frame.iconScale, layout.actionSize * 0.22f)
        actionPaint.color = android.graphics.Color.WHITE
        actionPaint.strokeWidth = 3f * frame.density
        actionPaint.style = Paint.Style.STROKE
        canvas.drawLine(cx - half, cy, cx + half, cy, actionPaint)
        canvas.drawLine(cx, cy - half, cx, cy + half, actionPaint)
    }

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true

    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) {
            drawSource()
            return true
        }
        // Clip by the moving lens geometry, including partially crossed slots.
        // Its inside copy already carries press scale and shader refraction.
        val outside = canvas.save()
        try {
            canvas.clipOutPath(selectionPath)
            drawSource()
        } finally { canvas.restoreToCount(outside) }
        if (mix < 1f) {
            val inside = canvas.save()
            try {
                canvas.clipPath(selectionPath)
                canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                    ((1f - mix) * 255f).toInt())
                drawSource()
            } finally { canvas.restoreToCount(inside) }
        }
        return true
    }

    override fun drawFixedNavigationSlot(canvas: Canvas, index: Int, selectedIndex: Int,
        dark: Boolean, centerX: Float, centerY: Float, boxSize: Float): Boolean {
        prepare(host.adapterNavigationSource) { _, _ -> }
        val config = host.config
        val density = host.resources.displayMetrics.density
        val frame = AdapterNavigationFrame(host.context, 1f, items.size, host.width, host.height,
            boxSize, density, config.iconScale, config.textSize,
            host.resources.displayMetrics.scaledDensity, selectedIndex, items.indices.toList(),
            config.iconOnly || config.bottomBarIconOnly, dark, 0)
        drawSlot(canvas, index, centerX, if (frame.iconOnly) host.height / 2f else centerY, frame, 1f)
        return true
    }

    private fun drawSlot(canvas: Canvas, index: Int, centerX: Float, centerY: Float,
        frame: AdapterNavigationFrame, extraScale: Float) {
        val item = items.getOrNull(index) ?: return
        // MineTabView.p() hides tab_icon/tab_icon_lav and loads the current
        // account avatar into ZHDraweeView#avatar via native setImageURI.
        // Draw that live View: preserve its circle, placeholder and updates.
        val icon = item.avatar?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            ?: item.animation?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            ?: item.icon?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
        val isCenterAction = items.size == 5 && index == 2
        val label = item.label?.takeIf { !isCenterAction && (!frame.iconOnly || icon == null) && it.visibility == View.VISIBLE && it.text.isNotEmpty() }
        val enlargePageIcon = items.size == 5 && index in listOf(0, 1, 3)
        val iconMultiplier = if (isCenterAction) 1.6f * 1.2f * 1.15f else if (enlargePageIcon) 1.2f * 1.12f else 1.2f
        val iconSize = if (icon == null) 0f else 24f * frame.density * frame.iconScale * iconMultiplier
        labelPaint.textSize = frame.textSize * frame.scaledDensity
        labelPaint.typeface = label?.typeface
        val fm = labelPaint.fontMetrics
        val labelHeight = if (label == null) 0f else fm.descent - fm.ascent
        val gap = if (icon != null && label != null) (if (enlargePageIcon) 0.5f else 1.5f) * frame.density else 0f
        val top = centerY - (iconSize + gap + labelHeight) / 2f
        val labelBaseline = top + iconSize + gap - fm.ascent
        val save = canvas.save()
        try {
            canvas.scale(extraScale, extraScale, centerX, centerY)
            icon?.let {
                val scale = iconSize / maxOf(it.width, it.height).coerceAtLeast(1)
                val x = centerX - it.width * scale / 2f
                val y = top + (iconSize - it.height * scale) / 2f
                val artwork = if (frame.hideSelectedForOptics) item.selectedArtwork else item.idleArtwork
                if (it !== item.avatar && artwork != null) {
                    val leaf = canvas.save()
                    try {
                        canvas.translate(x, y)
                        canvas.scale(scale, scale)
                        artwork.setBounds(0, 0, it.width, it.height)
                        artwork.draw(canvas)
                    } finally { canvas.restoreToCount(leaf) }
                } else {
                    // Avatars remain live Fresco content; do not flatten/tint them.
                    drawLeaf(canvas, it, x, y, scale)
                }
                it.getLocationInWindow(iconPosition)
                for (badge in item.badges) {
                    if (badge.visibility != View.VISIBLE || badge.width <= 0 || badge.height <= 0) continue
                    badge.getLocationInWindow(badgePosition)
                    drawLeaf(canvas, badge, x + (badgePosition[0] - iconPosition[0]) * scale,
                        y + (badgePosition[1] - iconPosition[1]) * scale, scale)
                }
            }
            label?.let {
                labelPaint.color = it.textColors.getColorForState(
                    if (frame.hideSelectedForOptics) selectedState else idleState, it.textColors.defaultColor)
                canvas.drawText(it.text.toString(), centerX, labelBaseline, labelPaint)
            }
        } finally { canvas.restoreToCount(save) }
    }

    private fun drawLeaf(canvas: Canvas, view: View, x: Float, y: Float, scale: Float) {
        val save = canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            // Direct leaf drawing preserves native resource/tint/badge backgrounds,
            // bypassing only the hidden shell. No row or page bitmap is captured.
            view.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    override fun onHostDetached() = dispose()
    override fun dispose() {
        source = null; items = emptyList(); refreshAt = 0L
        onOpticalRendererConfigurationChanged()
    }
}
