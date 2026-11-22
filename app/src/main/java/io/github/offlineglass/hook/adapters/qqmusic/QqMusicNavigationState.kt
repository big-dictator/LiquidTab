package io.github.offlineglass.hook.adapters.qqmusic

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.localizeNavigationLabel
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-renderer state. Selection is resolved by item identity (the semantic
 * getItem/getSelectedItem accessors return the same instance for the selected
 * cell) and artwork is read from each cell's own view tree — never from the
 * obfuscated holder accessors that QQMusic renames between builds.
 */
internal class QqMusicNavigationState(private val host: GlassHostLayout) : AppNavigationState {
    private val isPlayer get() = QqMusicBinding.isPlayerRoot(host.adapterNavigationSource ?: host)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelBounds = Rect()
    private val holders = HashMap<View, QqMusicBinding.Artwork>()
    private val nativeIcons = HashMap<View, QqMusicBinding.NativeIconPair>()
    private var artworkStamp = 0
    private var lastCells: List<View> = emptyList()
    private val contentInsets = QqMusicContentInsets()
    private val sceneLocation = IntArray(2)
    private val hostLocation = IntArray(2)
    private var recordedScene: java.lang.ref.WeakReference<View>? = null
    private var recordedGeometry = Long.MIN_VALUE
    private var recordedPage = -1
    private var videoScrollHideActive = false

    private fun sceneGeometry(scene: View): Long {
        scene.getLocationInWindow(sceneLocation)
        host.getLocationInWindow(hostLocation)
        var stamp = 17L
        stamp = stamp * 31 + scene.width
        stamp = stamp * 31 + scene.height
        stamp = stamp * 31 + host.width
        stamp = stamp * 31 + host.height
        stamp = stamp * 31 + (sceneLocation[0] - hostLocation[0])
        stamp = stamp * 31 + (sceneLocation[1] - hostLocation[1])
        return stamp
    }

    // Retain the GPU scene reference, never a bitmap. Android updates its child
    // display lists normally; scrolling pixels stay live without a second sweep.
    override fun retainHomeBackdrop(selectedIndex: Int): Boolean {
        val scene = resolveBackdropScene(host) ?: return false
        return recordedPage == selectedIndex && recordedScene?.get() === scene &&
            recordedGeometry == sceneGeometry(scene)
    }

    override fun onLiveBackdropRecorded(selectedIndex: Int) {
        val scene = resolveBackdropScene(host) ?: return
        recordedScene = java.lang.ref.WeakReference(scene)
        recordedGeometry = sceneGeometry(scene)
        recordedPage = selectedIndex
    }

    override fun allowsHostTouch() = !isPlayer
    override val allowsSoftwareBackdropCapture = false
    override fun drawsNavigationContent() = !isPlayer
    override fun suppressRedrawnSource() = !isPlayer
    // Keep the blurred backdrop layer that gives the moving pill its glass optics,
    // while excluding the hidden native row that caused blue icon ghosts.
    override fun drawHiddenTabsInIndicatorScene(hybridBackdrop: Boolean) = !isPlayer
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = !isPlayer
    private var foregroundIdle = false

    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        if (isPlayer) return false
        val save = canvas.save()
        canvas.clipOutPath(selectionPath)
        foregroundIdle = true
        try { draw(canvas, frame) } finally {
            foregroundIdle = false
            canvas.restoreToCount(save)
        }
        return true
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        if (isPlayer) return 0
        source ?: return null
        return runCatching {
            val selected = XposedHelpers.callMethod(source, "getSelectedItem") ?: return@runCatching null
            cells(source).indexOfFirst { cell ->
                XposedHelpers.callMethod(source, "getItem", cell.tag as Int) === selected
            }.takeIf { it in 0 until slotCount }
        }.getOrNull()
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        if (isPlayer || source == null) return
        val current = cells(source)
        val stamp = holders.values.fold(host.resources.configuration.hashCode()) { hash, art ->
            art.icons.fold(hash) { value, icon -> 31 * value + System.identityHashCode(icon.drawable) }
        }
        if (current == lastCells && stamp == artworkStamp) return
        holders.clear()
        lastCells = current
        nativeIcons.clear()
        for (cell in current) {
            holders[cell] = QqMusicBinding.extractArtwork(cell)
            QqMusicBinding.nativeIconPair(source, cell)?.let { nativeIcons[cell] = it }
        }
        artworkStamp = holders.values.fold(host.resources.configuration.hashCode()) { hash, art ->
            art.icons.fold(hash) { value, icon -> 31 * value + System.identityHashCode(icon.drawable) }
        }
    }

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
        density: Float, barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        if (isPlayer) {
            QqMusicPlayerGlass.updateGeometry(this.host)
            return
        }
        source ?: return
        val shell = source.parent as? ViewGroup ?: return
        QqMusicNativeChrome.clear(shell, this.host.config)
        QqMusicPlayerGlass.attach(shell)
        // Hidden native cells remain controller endpoints, but must not receive real
        // touches outside the floating glass panel. performClick still invokes listeners.
        source.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        source.isClickable = false
        cells(source).forEach { it.isClickable = false }
        val count = cells(source).size
        val selected = resolveSelectedIndex(source, count)
        val videoPage = enabled && selected == 1 && QqMusicNativeChrome.navigationPageVisible(source)
        if (videoPage != videoScrollHideActive) {
            videoScrollHideActive = videoPage
            if (videoPage) this.host.adapterOnScrollStopHidePageEntered(1_500L)
            else this.host.adapterCancelScrollStopHide()
        }
        contentInsets.update(source, selected.takeIf {
            enabled && QqMusicNativeChrome.navigationPageVisible(source) && it in 0..4
        })
        if (count in 2..7) this.host.updateAdapterSlotCount(count, resolveSelectedIndex(source, count) ?: 0)
    }

    override fun onHostFrame(host: View) {
        if (isPlayer) QqMusicPlayerGlass.updateGeometry(this.host)
    }

    override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true

    override fun dispatchNativeNavigationTap(
        host: GlassHostLayout, source: ViewGroup?, index: Int, slotCount: Int,
    ): Boolean {
        if (isPlayer || source == null) return false
        return cells(source).getOrNull(index)?.performClick() == true
    }

    override fun resolveBackdropScene(host: View) = this.host.adapterNavigationSource?.let(QqMusicNativeChrome::scene)

    // A missing app-specific backdrop must never suppress the whole glass panel.
    // Live sampling can fall back to the host's actual parent scene independently.
    override fun beforeHostDraw(host: View, source: ViewGroup?) =
        nativeContentAllowsNavigation()

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean {
        return nativeContentAllowsNavigation() && (isPlayer || !videoScrollHideActive || !host.msgScrolledToStop)
    }

    private fun nativeContentAllowsNavigation(): Boolean {
        val source = host.adapterNavigationSource ?: return false
        return source.isAttachedToWindow && source.isShown && source.width > 0 && source.height > 0 &&
            (if (isPlayer) source.alpha > 0.001f && QqMusicPlayerGlass.hasVisibleNativeContent(source)
            else cells(source).size >= 2 && QqMusicNativeChrome.navigationPageVisible(source))
    }

    override fun handleSystemBack(host: View): Boolean {
        if (isPlayer || !videoScrollHideActive || !nativeContentAllowsNavigation()) return false
        this.host.adapterOnScrollStopHidePageEntered(1_500L)
        this.host.postInvalidateOnAnimation()
        return true
    }

    override fun forceNavigationBarImmersed(host: GlassHostLayout): Boolean {
        // Both renderer listeners use one page decision, so a hidden navigation
        // panel cannot restore an inset while a sub-page still displays its player.
        val source = host.adapterNavigationSource ?: return false
        var shell: View? = source
        while (shell != null && shell.javaClass.name != QqMusicNativeChrome.SHELL_CLASS) shell = shell.parent as? View
        val group = shell as? ViewGroup ?: return false
        val nav = QqMusicNativeChrome.navigation(group)
        val player = QqMusicNativeChrome.player(group)
        return host.config.enabled && ((nav?.isShown == true && QqMusicNativeChrome.navigationPageVisible(nav)) ||
            (player?.isShown == true && player.alpha > 0.001f && player.height > 0 &&
                QqMusicPlayerGlass.hasVisibleNativeContent(player)))
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (isPlayer) return
        val source = host.adapterNavigationSource ?: return
        val current = cells(source)
        val iconSize = min(28f * frame.density * frame.iconScale, frame.height * 0.52f) * 1.32f
        val textSize = frame.textSize * frame.scaledDensity
        val hasLabel = !frame.iconOnly
        val labelGap = 2f * frame.density
        val totalHeight = iconSize + if (hasLabel) labelGap + textSize else 0f
        val iconTop = (frame.height - totalHeight) / 2f - if (hasLabel) 2f * frame.density else 0f
        for ((visual, index) in frame.enabledIndices.withIndex()) {
            val cell = current.getOrNull(index) ?: continue
            val art = holders[cell] ?: continue
            val center = 4f * frame.density + (visual + 0.5f) * frame.slotWidth
            val save = canvas.save()
            canvas.scale(frame.extraScale, frame.extraScale, center, frame.height / 2f)
            val image = art.icons.singleOrNull {
                io.github.offlineglass.hook.adapters.AdapterNavigationSearch.resourceEntryName(it) ==
                    "main_desk_bottom_navigate_item_img"
            } ?: art.icons.firstOrNull { it.visibility == View.VISIBLE && it.drawable != null }
                ?: art.icons.firstOrNull { it.drawable != null }
            val pair = nativeIcons[cell]
            (pair?.let { if (foregroundIdle) it.normal else it.selected } ?: image?.drawable)?.let { drawable ->
                val oldState = drawable.state.clone()
                val oldFilter = drawable.colorFilter
                val oldAlpha = drawable.alpha
                // QQMusic applies night/skin colours on the ImageView, after
                // loading the resource. Reuse that native presentation as well.
                drawable.colorFilter = image?.colorFilter
                drawable.alpha = image?.imageAlpha ?: 255
                image?.drawableState?.let { drawable.state = it }
                val oldBounds = Rect(drawable.bounds)
                val ratio = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
                    drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight else 1f
                val w = if (ratio > 1f) iconSize else iconSize * ratio
                val h = if (ratio > 1f) iconSize / ratio else iconSize
                val artworkTop = if (hasLabel) iconTop else (frame.height - h) / 2f
                drawable.setBounds((center - w / 2).roundToInt(), artworkTop.roundToInt(),
                    (center + w / 2).roundToInt(), (artworkTop + h).roundToInt())
                try { drawable.draw(canvas) } finally {
                    drawable.bounds = oldBounds
                    drawable.state = oldState
                    drawable.colorFilter = oldFilter
                    drawable.alpha = oldAlpha
                }
            }
            if (hasLabel) art.label?.let { label ->
                labelPaint.color = label.textColors.getColorForState(intArrayOf(android.R.attr.state_enabled,
                    if (foregroundIdle) -android.R.attr.state_selected else android.R.attr.state_selected),
                    label.currentTextColor)
                labelPaint.typeface = label.typeface
                labelPaint.textSize = textSize
                // The font ascent includes blank space above the visible glyphs.
                // Anchor their actual top 1dp below the icon, without moving iconTop.
                val text = frame.localizeNavigationLabel(label.text.toString())
                labelPaint.getTextBounds(text, 0, text.length, labelBounds)
                val baseline = iconTop + iconSize + frame.density - labelBounds.top
                canvas.drawText(text, center, baseline, labelPaint)
            }
            for (badge in art.badges) {
                if (badge.visibility != View.VISIBLE || badge.alpha <= 0.001f || badge.width <= 0 || badge.height <= 0) continue
                val badgeSave = canvas.save()
                canvas.translate(center + iconSize * 0.25f, iconTop - 2f * frame.density)
                val scale = min(1f, (24f * frame.density) / badge.width)
                canvas.scale(scale, scale)
                badge.draw(canvas)
                canvas.restoreToCount(badgeSave)
            }
            canvas.restoreToCount(save)
        }
    }

    override fun dispose() {
        host.adapterCancelScrollStopHide()
        videoScrollHideActive = false
        recordedScene = null
        recordedGeometry = Long.MIN_VALUE
        contentInsets.restore()
        holders.clear()
        nativeIcons.clear()
        lastCells = emptyList()
    }

    companion object {
        fun cells(source: ViewGroup): List<View> {
            if (!QqMusicNativeChrome.isNavigation(source)) return emptyList()
            // natively generated item roots are tagged with controller keys, in display order.
            return (0 until source.childCount).map { source.getChildAt(it) }
                .filter { it.tag is Int && it.visibility == View.VISIBLE }
        }
    }
}
