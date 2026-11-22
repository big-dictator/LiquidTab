package io.github.offlineglass.hook.adapters.instagram

import android.graphics.Canvas
import android.graphics.RenderNode
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.NavigationLabelLocalization

/**
 * Spotify 式滑块渲染的 IG 实现：
 * - 前景（滑块外）：无论原生选中状态，统一渲染非选中态（轮廓 glyph +
 *   idle 色）；
 * - 滑块内：无论原生状态，渲染选中态（实心 glyph + accent 色），并作为
 *   滑块组合场景的一部分参与液态玻璃折射采样（adapter 侧
 *   drawsNavigationInOptics + includeNavigationInIndicatorScene）。
 *
 * 共享层在滑块组合场景录制时置 hideSelectedForOptics=true
 * （GlassHostLayout 的 drawingHiddenNavigationForOptics），draw() 据此切换
 * artwork 与颜色——几何裁剪（drawForeground）而非选中页决定可见颜色，
 * 跨越滑块边界的图标立即恢复其外侧部分。
 *
 * IG 的原生 glyph 选中/非选中是不同形态（实心/轮廓，state-list drawable，
 * 不同于 Spotify 的同形异色），因此录制两份 RenderNode artwork：录制时对
 * tab_bar 子树（深度≤6，有界遍历）统一翻转 View.selected 后离屏录制，
 * 随即恢复原状态。录制由结构键（childCount/尺寸）+ 5 秒节流驱动，状态
 * 翻转引发的 invalidate 不会形成录制循环。
 */
internal class InstagramNavigationState : AppNavigationState {
    private val feedTopInset = InstagramFeedTopInset()
    private var sourceRef: java.lang.ref.WeakReference<ViewGroup>? = null
    private val idleArtwork = RenderNode("ig-nav-idle")
    private val activeArtwork = RenderNode("ig-nav-active")
    private var artworkKey = ""
    private var artworkRecordedAt = 0L

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        if (source != null) sourceRef = java.lang.ref.WeakReference(source)
        feedTopInset.prepare(source)
    }

    override fun onHostFrame(host: View) {
        feedTopInset.prepare(sourceRef?.get())
        feedTopInset.update()
    }

    override fun onHostDetached() = feedTopInset.clear()

    override fun dispose() {
        feedTopInset.clear()
        sourceRef = null
    }

    override val readyForDrawing: Boolean get() = sourceRef?.get() != null

    /** 导航内容纳入滑块（indicator）的组合采样场景，随玻璃折射。 */
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true

    /**
     * 按滑块几何逐帧分割前景（Spotify 同款）：滑块外正常绘制，滑块内按
     * (1-mix) 淡出，让滑块玻璃的折射采样成为滑块内图标的唯一视觉来源。
     */
    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) { drawSource(); return true }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        val source = sourceRef?.get() ?: return
        if (!canvas.isHardwareAccelerated || source.width <= 0 || source.height <= 0) return
        recordArtworkIfNeeded(source, frame.managerEnglishLabels)
        val active = frame.hideSelectedForOptics
        val artwork = (if (active) activeArtwork else idleArtwork).takeIf { it.hasDisplayList() } ?: return
        val slots = frame.slotCount.coerceAtLeast(1)
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val srcSlotW = w / slots
        data class LocalizedLabel(val slot: Int, val x: Float, val baseline: Float, val text: String,
                                  val paint: android.text.TextPaint)
        val localizedLabels = if (frame.managerEnglishLabels) buildList {
            val sourcePosition = IntArray(2).also(source::getLocationInWindow)
            walkBounded(source) { view ->
                val label = view as? TextView ?: return@walkBounded
                val native = label.text?.toString().orEmpty()
                val labelPosition = IntArray(2).also(label::getLocationInWindow)
                val x = labelPosition[0] - sourcePosition[0] + label.width / 2f
                val slot = (x / srcSlotW).toInt().coerceIn(0, slots - 1)
                val english = englishLabel(native, slot)
                if (native.isEmpty() || english == native) return@walkBounded
                val paint = android.text.TextPaint(label.paint).apply {
                    color = label.textColors.getColorForState(
                        if (slot == frame.selectedIndex) intArrayOf(android.R.attr.state_selected) else intArrayOf(),
                        label.textColors.defaultColor,
                    )
                    textAlign = android.graphics.Paint.Align.CENTER
                    val available = (srcSlotW - 8f * frame.density).coerceAtLeast(1f)
                    val measured = measureText(english)
                    if (measured > available) textScaleX *= available / measured
                }
                add(LocalizedLabel(slot, x,
                    (labelPosition[1] - sourcePosition[1] + label.baseline).toFloat(), english, paint))
            }
        } else emptyList()
        // 1.3× 放大（受行高 0.85 上限约束），绕槽位中心缩放后垂直居中。
        // 不做任何 tint：实心/轮廓 glyph 直接以原生色彩呈现原本的选中态
        // 与非选中态，滑块几何只决定"哪份状态可见"。
        val baseScale = minOf(frame.slotWidth / srcSlotW, frame.height / h)
        val iconScale = minOf(baseScale * 1.3f, frame.height * 0.85f / h) * frame.extraScale
        val top = (frame.height - h * iconScale) / 2f
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            if (index < 0 || index >= slots) return@forEachIndexed
            val cx = 4f * frame.density + (visualIndex + 0.5f) * frame.slotWidth
            val srcCx = (index + 0.5f) * srcSlotW
            val saved = canvas.save()
            canvas.translate(cx, top)
            canvas.scale(iconScale, iconScale)
            canvas.translate(-srcCx, 0f)
            canvas.clipRect(index * srcSlotW, 0f, (index + 1) * srcSlotW, h)
            canvas.drawRenderNode(artwork)
            localizedLabels.filter { it.slot == index }.forEach { canvas.drawText(it.text, it.x, it.baseline, it.paint) }
            canvas.restoreToCount(saved)
        }
    }

    private fun recordArtworkIfNeeded(source: ViewGroup, managerEnglishLabels: Boolean) {
        val key = "${source.childCount}/${source.width}x${source.height}/en=$managerEnglishLabels"
        val now = android.os.SystemClock.uptimeMillis()
        if (key == artworkKey && idleArtwork.hasDisplayList() && activeArtwork.hasDisplayList() &&
            now - artworkRecordedAt < 5_000L) return
        artworkKey = key
        artworkRecordedAt = now
        runCatching {
            val original = ArrayList<Pair<View, Boolean>>()
            walkBounded(source) { original += it to it.isSelected }
            val localizedLabels = if (managerEnglishLabels) buildList {
                val sourcePosition = IntArray(2).also(source::getLocationInWindow)
                val slotWidth = source.width.toFloat() / 5f
                walkBounded(source) { view ->
                    val label = view as? TextView ?: return@walkBounded
                    val native = label.text?.toString().orEmpty()
                    val position = IntArray(2).also(label::getLocationInWindow)
                    val centerX = position[0] - sourcePosition[0] + label.width / 2f
                    val slot = (centerX / slotWidth.coerceAtLeast(1f)).toInt().coerceIn(0, 4)
                    if (native.isNotEmpty() && englishLabel(native, slot) != native)
                        add(label to label.visibility)
                }
            } else emptyList()
            try {
                localizedLabels.forEach { (view, _) -> view.visibility = View.INVISIBLE }
                walkBounded(source) { if (it.isSelected) it.isSelected = false }
                recordTo(idleArtwork, source)
                walkBounded(source) { if (!it.isSelected) it.isSelected = true }
                recordTo(activeArtwork, source)
            } finally {
                localizedLabels.forEach { (view, visibility) -> view.visibility = visibility }
                original.forEach { (view, selected) -> if (view.isSelected != selected) view.isSelected = selected }
            }
        }
    }

    private fun englishLabel(label: String, slot: Int): String = when (label.trim()) {
        // Instagram's Chinese "主页" can mean Home or the profile tab.
        // Its native slot order is feed, Reels, messages, search, profile.
        "主页" -> if (slot == 4) "Profile" else "Home"
        else -> NavigationLabelLocalization.toEnglish("instagram", label)
    }

    /** 有界遍历（深度≤6），不做全树递归。 */
    private fun walkBounded(root: ViewGroup, action: (View) -> Unit) {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            action(view)
            if (view is ViewGroup && depth < 6) {
                for (index in 0 until view.childCount) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
    }

    private fun recordTo(node: RenderNode, source: ViewGroup) {
        val recording = node.beginRecording(source.width, source.height)
        try { source.draw(recording) } finally { node.endRecording() }
        node.setPosition(0, 0, source.width, source.height)
    }
}
