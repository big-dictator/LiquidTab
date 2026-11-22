package io.github.offlineglass.hook.adapters.chatgpt

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RenderNode
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import kotlin.math.roundToInt
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.glassHostHeightPx
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Compose owns the real tabs, their accessibility actions, and page selection. */
internal object ChatGptBottomBar {
    data class Icon(val id: Int, val bounds: Rect)
    data class Chrome(val id: Int, val bounds: Rect)
    data class Candidate(
        val rowId: Int,
        val bounds: Rect,
        val slots: List<Int>,
        val selected: Int,
        val icons: List<Icon?>,
        val chrome: List<Chrome>,
    )

    class NativeDraw internal constructor(
        val scope: Canvas,
        private val finish: (Boolean) -> Unit,
    ) {
        fun close(success: Boolean) = finish(success)
    }

    private data class HomeInsets(val windowWidth: Int, val densityDpi: Int, val left: Int, val right: Int)
    private val homeInsets = WeakHashMap<Activity, HomeInsets>()
    private val byActivity = WeakHashMap<Activity, Binding>()
    private val byHost = WeakHashMap<GlassHostLayout, Binding>()
    private val byRow = WeakHashMap<Any, Binding>()
    private val byIcon = WeakHashMap<Any, Glyph>()
    private val hiddenChrome = WeakHashMap<Any, Binding>()
    private val retiringRows = WeakHashMap<Any, Binding>()
    private val b get() = ChatGptBindings
    private const val PACKAGE = "com.openai.chatgpt"

    private val artworkWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-ChatGPT-glyphs").apply { isDaemon = true }
    }
    private val rasterize by lazy {
        runCatching {
            HardwareRenderer::class.java.getDeclaredMethod(
                "createHardwareBitmap", RenderNode::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    fun verifiedSelection(anchor: View): Int? =
        ChatGptWindows.activity(anchor.context)?.let { activity ->
            byActivity[activity]?.takeIf { it.anchor === anchor && it.hasPresentedArtwork() }?.selected
        }

    fun update(activity: Activity, anchor: View, candidate: Candidate?) {
        val current = byActivity[activity]
        if (candidate == null) {
            if (current?.anchor === anchor && !current.nativePlaced()) current.awaitNative()
            return
        }
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val row = runCatching { findRow(anchor, candidate) }.getOrNull()
        if (!HookConfigReader.read(activity, PACKAGE).enabled) {
            remove(activity)
            return
        }
        if (row == null) {
            ChatGptDiagnostics.report(activity, "bottomBar", "tab semantics found; native layout ownership unresolved")
            current?.awaitNative()
            return
        }
        val icons = runCatching { findIcons(anchor, row, candidate) }.getOrNull()
        if (icons == null && current?.hasPresentedArtwork() != true) {
            current?.awaitNative()
            ChatGptDiagnostics.report(activity, "bottomBarArtwork", "native icon ownership unresolved")
            return
        }
        val chrome = candidate.chrome.mapNotNull { runCatching { findChrome(anchor, row, it) }.getOrNull() }
        if (current != null && current.content === content) {
            if (current.row === row && current.anchor === anchor &&
                (icons == null || current.glyphs.map { it.layout } == icons)) {
                current.updateChrome(chrome)
                current.updateCandidate(candidate)
            } else current.replaceNative(anchor, row, chrome, candidate, icons.orEmpty())
            return
        }
        remove(activity)
        val binding = Binding(content, anchor, row, chrome, candidate, icons ?: return)
        byActivity[activity] = binding
        byHost[binding.host] = binding
        binding.registerNative()
        binding.allowOutsideDrawing()
        content.addView(binding.host, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        content.viewTreeObserver.addOnPreDrawListener(binding.frames)
        ChatGptDiagnostics.report(activity, "bottomBar", "bound native three-slot navigation; bottomDecorations=${chrome.size}")
    }

    private fun findRow(anchor: View, candidate: Candidate): Any? {
        val layouts = candidate.slots.map { b.semanticLayout(anchor, it) ?: return null }
        var row = b.semanticLayout(anchor, candidate.rowId) ?: b.parent(layouts.first())
        repeat(12) {
            val current = row ?: return null
            if (b.placed(current) && b.read("layout.owner", current) === anchor &&
                layouts.all { b.contains(current, it, onLimit = false) }) {
                val area = ChatGptComposeBackground.coordinateBounds(b.outer(current), anchor)
                if (area != null && matches(area, candidate.bounds)) return current
            }
            row = b.parent(current)
        }
        return null
    }

    private fun findIcons(anchor: View, row: Any, candidate: Candidate): List<Any>? {
        val layouts = candidate.icons.mapIndexed { index, icon ->
            if (icon == null) return null
            val slot = b.semanticLayout(anchor, candidate.slots[index]) ?: return null
            val layout = b.semanticLayout(anchor, icon.id) ?: return null
            if (layout === slot || layout === row || !b.contains(slot, layout, onLimit = false) ||
                !b.placed(layout) || b.read("layout.owner", layout) !== anchor) return null
            val area = ChatGptComposeBackground.coordinateBounds(b.outer(layout), anchor) ?: return null
            if (!matches(area, icon.bounds)) return null
            layout
        }
        return layouts.takeIf { it.distinct().size == candidate.slots.size }
    }

    private fun findChrome(anchor: View, row: Any, chrome: Chrome): Any? {
        val layout = b.semanticLayout(anchor, chrome.id) ?: return null
        if (!b.placed(layout) || b.read("layout.owner", layout) !== anchor || b.children(layout).isNotEmpty()) return null
        val parent = b.parent(layout) ?: return null
        if (layout === row || !b.contains(parent, row, onLimit = false) || b.contains(row, layout)) return null
        val rect = ChatGptComposeBackground.coordinateBounds(b.outer(layout), anchor) ?: return null
        return layout.takeIf { matches(rect, chrome.bounds) }
    }

    private fun matches(a: Rect, b: Rect): Boolean =
        kotlin.math.abs(a.left - b.left) <= 4 && kotlin.math.abs(a.top - b.top) <= 4 &&
            kotlin.math.abs(a.right - b.right) <= 4 && kotlin.math.abs(a.bottom - b.bottom) <= 4

    fun remove(activity: Activity) {
        val binding = byActivity.remove(activity) ?: return
        byHost.remove(binding.host)
        binding.dispose()
    }

    fun refreshConfig(appearanceChanged: Boolean) {
        byActivity.entries.toList().forEach { (activity, binding) ->
            if (!HookConfigReader.read(activity, PACKAGE).enabled) remove(activity)
            else {
                binding.host.refreshAdapterDerivedConfig()
                if (appearanceChanged) binding.refreshStableArtwork()
            }
        }
    }

    fun capturesNativeLayout(layout: Any): Boolean = byRow.containsKey(layout) || byIcon.containsKey(layout)
    fun ownsNativeRowDrawing(layout: Any): Boolean = byRow[layout]?.ownsRowDrawing() == true
    fun onBackdropReady(row: Any) {
        byRow[row]?.host?.postInvalidateOnAnimation()
    }

    fun suppressesChrome(layout: Any): Boolean =
        hiddenChrome.containsKey(layout) || retiringRows.containsKey(layout)
    fun beginNativeDraw(layout: Any, canvas: Canvas): NativeDraw? =
        byIcon[layout]?.begin(canvas) ?: byRow[layout]?.beginNativeDraw(canvas)

    /** Preserve the native coordinates when substituting the recording canvas. */
    private class Capture(name: String, private val anchor: View, private val layout: Any) {
        private val node = RenderNode(name).apply { setClipToBounds(false) }
        private val parentToScreen = Matrix()
        private val screenToParent = Matrix()
        private val matrixBuffer = FloatArray(9)
        var width = 0
            private set
        var height = 0
            private set
        private var busy = false
        fun ready() = width > 0 && height > 0 && node.hasDisplayList()

        fun begin(canvas: Canvas, replay: () -> Boolean, onFinished: (Boolean) -> Unit): NativeDraw? {
            if (busy || !canvas.isHardwareAccelerated) return null
            val parent = b.parent(layout)?.let(b::inner) ?: return null
            val area = ChatGptComposeBackground.coordinateBounds(b.outer(layout), anchor) ?: return null
            if (area.isEmpty || area.width() > anchor.width + 4 || area.height() > anchor.height / 3) return null
            val screen = ChatGptComposeBackground.coordinateMatrix(parent, output = parentToScreen, values = matrixBuffer)
            if (!screen.invert(screenToParent)) return null
            node.setPosition(0, 0, area.width(), area.height())
            val scope = node.beginRecording(area.width(), area.height())
            busy = true
            scope.translate(-area.left.toFloat(), -area.top.toFloat())
            scope.concat(screen)
            return NativeDraw(scope) { success ->
                try {
                    node.endRecording()
                    if (success) {
                        width = area.width()
                        height = area.height()
                        if (replay()) {
                            val save = canvas.save()
                            try {
                                canvas.concat(screenToParent)
                                canvas.translate(area.left.toFloat(), area.top.toFloat())
                                canvas.drawRenderNode(node)
                            } finally {
                                canvas.restoreToCount(save)
                            }
                        }
                    } else {
                        clear()
                    }
                    onFinished(success)
                } finally {
                    busy = false
                }
            }
        }

        fun draw(canvas: Canvas) = canvas.drawRenderNode(node)
        fun snapshot(): RenderNode? {
            if (!ready()) return null
            val captureWidth = width
            val captureHeight = height
            return RenderNode("LiquidTab-ChatGPT-glyph-copy").apply {
                setPosition(0, 0, captureWidth, captureHeight)
                val canvas = beginRecording()
                try { canvas.drawRenderNode(node) } finally { endRecording() }
            }
        }

        fun hideReplay(hidden: Boolean) {
            node.setAlpha(if (hidden) 0f else 1f)
        }
        fun clear() {
            width = 0
            height = 0
            node.discardDisplayList()
        }
    }

    private class Glyph(val layout: Any, anchor: View, onReady: (Boolean) -> Unit) {
        val drawing = Capture("LiquidTab-ChatGPT-native-glyph", anchor, layout)
        fun begin(canvas: Canvas): NativeDraw? {
            val first = !drawing.ready()
            return drawing.begin(canvas, { true }) { success ->
                if (first || !success) onReady(success)
            }
        }

        private val onReady = onReady
    }

    private class Binding(
        val content: FrameLayout,
        anchor: View,
        row: Any,
        chrome: List<Any>,
        private var candidate: Candidate,
        icons: List<Any>,
    ) {
        val host = GlassHostLayout(content.context, HookConfigReader.read(content.context, PACKAGE),
            3, nativeSelectionReliable = true, primaryNavigationHost = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
            elevation = 0f
            alpha = 0f
        }
        private val originalClipChildren = content.clipChildren
        private val originalClipToPadding = content.clipToPadding

        fun allowOutsideDrawing() {
            // Match GlassInstaller's overflow contract for shadows and the
            // expanded indicator. Compose's navigation row stays in place.
            content.clipChildren = false
            content.clipToPadding = false
        }

        var anchor = anchor
            private set
        var row = row
            private set
        private var chrome = chrome
        private var rowCapture = Capture("LiquidTab-ChatGPT-native-navigation", anchor, row)
        private var active = false
        private var disposed = false
        private var generation = 0
        private var visibleGlyphs: List<Glyph> = emptyList()
        var glyphs: List<Glyph> = createGlyphs(icons)
            private set
        private val stableArtwork = arrayOfNulls<Bitmap>(3)
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var artworkRevision = 0
        private var artworkPending = false
        private var captureAttempts = 0
        private val captureArtwork = Runnable { captureStableArtwork() }

        fun refreshStableArtwork() {
            artworkRevision++
            captureAttempts = 0
            content.removeCallbacks(captureArtwork)
            content.postDelayed(captureArtwork, 140L)
        }

        private fun captureStableArtwork() {
            if (disposed || artworkPending || !glyphsReady() || captureAttempts >= 4) return
            val raster = rasterize ?: return
            val inputs = glyphs.map { it.drawing.snapshot() ?: return }
            val revision = artworkRevision
            captureAttempts++
            artworkPending = true
            artworkWorker.execute {
                val results = inputs.map { node ->
                    runCatching {
                        val gpu = raster.invoke(null, node, node.width, node.height) as? Bitmap ?: return@runCatching null
                        val pixels = try { gpu.copy(Bitmap.Config.ARGB_8888, false) } finally { gpu.recycle() }
                            ?: return@runCatching null
                        val alpha = IntArray(pixels.width * pixels.height)
                        pixels.getPixels(alpha, 0, pixels.width, 0, 0, pixels.width, pixels.height)
                        if (alpha.count { it ushr 24 > 24 } >= 8) pixels
                        else { pixels.recycle(); null }
                    }.getOrNull()
                }
                content.post {
                    artworkPending = false
                    if (disposed || revision != artworkRevision) {
                        results.filterNotNull().forEach(Bitmap::recycle)
                        if (!disposed) content.postDelayed(captureArtwork, 140L)
                        return@post
                    }
                    results.forEachIndexed { slot, bitmap ->
                        // A native transition may briefly clear the icon layer.
                        // Keep the previously presented native pixels in that case.
                        if (bitmap != null) stableArtwork[slot] = bitmap
                    }
                    host.invalidateNavigationSnapshot()
                    if (results.any { it == null } && captureAttempts < 4) content.postDelayed(captureArtwork, 240L)
                }
            }
        }
        private var waitingSince = -1L
        private var nextScan = 0L
        private var probeUntil = 0L
        private var pendingSelection = -1
        private var parked = false
        private var dragging = false
        private var lastBackdropCheck = 0L
        private val expire = object : Runnable {
            override fun run() {
                if (disposed || waitingSince < 0L) return
                if (!nativeRowPlaced() && (dragging || host.isSelectionMotionRunning())) {
                    content.postDelayed(this, 150L)
                    return
                }
                if (!nativeRowPlaced()) {
                    parked = true
                    setActive(false)
                }
                waitingSince = -1L
                releaseRetiringRows()
            }
        }

        fun hasPresentedArtwork() = visibleGlyphs.isNotEmpty()
        fun ownsRowDrawing() = active && hasPresentedArtwork()
        private fun nativeRowPlaced() = anchor.isAttachedToWindow && anchor.isShown && b.placed(row)

        private fun releaseRetiringRows() {
            val rows = retiringRows.entries.filter { it.value === this }.map { it.key }
            rows.forEach {
                retiringRows.remove(it)
                if (b.placed(it)) b.invalidateParent(it)
            }
        }

        private fun createGlyphs(icons: List<Any>): List<Glyph> {
            val expectedGeneration = generation
            return icons.map { layout ->
                Glyph(layout, anchor) { success ->
                    // Commit all three glyphs from the same native generation.
                    anchor.post {
                        if (disposed || expectedGeneration != generation) return@post
                        if (success && glyphsReady()) commitGlyphs()
                        else if (!success) awaitNative()
                    }
                }
            }
        }

        private fun commitGlyphs() {
            if (visibleGlyphs === glyphs) return
            visibleGlyphs.filterNot(glyphs::contains).forEach { it.drawing.clear() }
            visibleGlyphs = glyphs
            refreshStableArtwork()
            ChatGptDiagnostics.report(anchor.context, "bottomBarArtwork", "native glyphs committed; generation=$generation")
            host.invalidateNavigationSnapshot()
        }

        fun nativePlaced() = nativeRowPlaced() && glyphs.all { b.placed(it.layout) }

        private fun glyphsReady() = glyphs.size == 3 && nativePlaced() && glyphs.all { it.drawing.ready() }

        fun registerNative() {
            retiringRows.remove(row)
            byRow[row] = this
            glyphs.forEach { byIcon[it.layout] = it }
            glyphs.forEach { b.invalidateParent(it.layout) }
            b.invalidateParent(row)
            ChatGptPageSource.bindNavigation(row, anchor, chrome)
            anchor.invalidate()
        }

        private fun unregisterNative() {
            ChatGptPageSource.unbind(row)
            if (byRow[row] === this) byRow.remove(row)
            glyphs.forEach { if (byIcon[it.layout] === it) byIcon.remove(it.layout) }
            updateChrome(emptyList())
            // A native parent may still hold the last replay node in its cache.
            rowCapture.hideReplay(true)
            if (b.placed(row)) runCatching { b.invalidateParent(row) }
        }

        fun updateChrome(next: List<Any>) {
            if (chrome == next) return
            (chrome - next.toSet()).forEach {
                if (hiddenChrome[it] === this) {
                    hiddenChrome.remove(it)
                    if (b.placed(it)) b.invalidateParent(it)
                }
            }
            if (active) (next - chrome.toSet()).forEach {
                hiddenChrome[it] = this
                b.invalidateParent(it)
            }
            chrome = next
            if (byRow[row] === this) ChatGptPageSource.bindNavigation(row, anchor, chrome)
        }

        fun backdrop() = ChatGptPageSource.navigationBackdrop(row)

        fun onGesture(phase: Int) {
            when (phase) {
                0 -> dragging = true
                2 -> {
                    dragging = false
                    probeUntil = SystemClock.uptimeMillis() + 650L
                    nextScan = 0L
                }
            }
        }

        fun replaceNative(nextAnchor: View, nextRow: Any, nextChrome: List<Any>, next: Candidate, icons: List<Any>) {
            val retiring = row.takeIf { active && it !== nextRow && b.placed(it) }
            unregisterNative()
            if (retiring != null) retiringRows[retiring] = this
            rowCapture.clear()
            glyphs.filterNot(visibleGlyphs::contains).forEach { it.drawing.clear() }
            val anchorChanged = anchor !== nextAnchor
            anchor = nextAnchor
            row = nextRow
            parked = false
            generation++
            glyphs = createGlyphs(icons)
            rowCapture = Capture("LiquidTab-ChatGPT-native-navigation", anchor, row)
            rowCapture.hideReplay(active)
            if (anchorChanged) host.invalidateOpticalSceneForAdapter()
            updateChrome(nextChrome)
            updateCandidate(next)
            registerNative()
            awaitNative()
            ChatGptDiagnostics.report(anchor.context, "bottomBar", "rebound native navigation; generation=$generation")
        }

        fun awaitNative() {
            val now = SystemClock.uptimeMillis()
            if (waitingSince < 0L) {
                waitingSince = now
                content.postDelayed(expire, 600L)
            }
            if (!parked && now >= nextScan) {
                nextScan = now + 100L
                ChatGptWindows.activity(anchor.context)?.let(ChatGptComposerGlass::requestScan)
            }
        }

        private fun resumeNative() {
            if (waitingSince < 0L) return
            waitingSince = -1L
            // Give the exiting row one frame of overlap after its successor is ready.
            content.removeCallbacks(expire)
            content.postOnAnimation { if (!disposed && waitingSince < 0L) releaseRetiringRows() }
        }

        fun updateCandidate(next: Candidate) {
            if (next.selected == pendingSelection) {
                pendingSelection = -1
                probeUntil = 0L
            }
            parked = false
            if (candidate == next) return
            candidate = next
            commitSelection(next.selected)
            host.invalidateNavigationSnapshot()
        }

        fun beginNativeDraw(canvas: Canvas): NativeDraw? {
            if (disposed) return null
            return rowCapture.begin(canvas, { !active }) { success ->
                if (!success) anchor.post { if (!disposed) awaitNative() }
            }
        }

        fun drawNativeIcons(canvas: Canvas, frame: AdapterNavigationFrame) {
            if (!canvas.isHardwareAccelerated) return
            val density = frame.density
            val scale = frame.iconScale * frame.extraScale * 1.3f
            frame.enabledIndices.forEachIndexed { position, slot ->
                if (slot == frame.selectedIndex && frame.hideSelectedForOptics) return@forEachIndexed
                val bitmap = stableArtwork.getOrNull(slot)
                val glyph = visibleGlyphs.getOrNull(slot)?.drawing?.takeIf { it.ready() }
                val halfWidth = (bitmap?.width ?: glyph?.width ?: return@forEachIndexed) * .5f
                val halfHeight = (bitmap?.height ?: glyph?.height ?: return@forEachIndexed) * .5f
                val save = canvas.save()
                try {
                    canvas.translate(4f * density + (position + .5f) * frame.slotWidth, frame.height * .5f)
                    canvas.scale(scale, scale)
                    canvas.clipRect(-halfWidth, -halfHeight, halfWidth, halfHeight)
                    canvas.translate(-halfWidth, -halfHeight)
                    if (bitmap != null) canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
                    else glyph?.draw(canvas)
                } finally {
                    canvas.restoreToCount(save)
                }
            }
        }

        var selected = candidate.selected
        private var lastSelectionCheck = 0L
        private val contentLocation = IntArray(2)
        val frames = ViewTreeObserver.OnPreDrawListener {
            val activity = ChatGptWindows.activity(anchor.context) ?: return@OnPreDrawListener true
            val config = HookConfigReader.read(activity, PACKAGE)
            if (!config.enabled || !content.isShown || !content.isAttachedToWindow) {
                remove(activity)
                return@OnPreDrawListener true
            }
            val now = SystemClock.uptimeMillis()
            val needsProbe = now < probeUntil || (!parked && (!nativePlaced() || glyphs.isEmpty()))
            if (needsProbe && now >= nextScan) {
                nextScan = now + 48L
                ChatGptComposerGlass.scanBottomBar(activity, anchor)
                if (disposed) return@OnPreDrawListener true
            }
            if (!nativePlaced()) {
                awaitNative()
                return@OnPreDrawListener true
            }
            val area = ChatGptComposeBackground.coordinateBounds(b.outer(row), anchor)
            if (area == null || area.isEmpty) {
                awaitNative()
                return@OnPreDrawListener true
            }
            if (glyphsReady()) {
                commitGlyphs()
                resumeNative()
            } else if (active) awaitNative()
            val density = content.resources.displayMetrics.density
            val slots = candidate.slots.indices.count { config.hiddenMask and (1 shl it) == 0 }.coerceAtLeast(1)
            val height = glassHostHeightPx(density, config, PACKAGE, slots)
            content.getLocationOnScreen(contentLocation)
            val metrics = content.resources.displayMetrics
            val windowWidth = content.width
            val composer = if (selected == 0 &&
                anchor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
            ) ChatGptComposerGlass.composerBounds(activity, anchor) else null
            composer?.let {
                val leftInset = it.left - contentLocation[0]
                val rightInset = contentLocation[0] + windowWidth - it.right
                val known = homeInsets[activity]
                if (leftInset >= 0 && rightInset >= 0 && it.width() >= windowWidth / 2 &&
                    (known?.windowWidth != windowWidth || known.densityDpi != metrics.densityDpi ||
                        known.left != leftInset || known.right != rightInset)
                ) {
                    homeInsets[activity] = HomeInsets(windowWidth, metrics.densityDpi, leftInset, rightInset)
                }
            }
            val reference = homeInsets[activity]?.takeIf {
                it.windowWidth == windowWidth && it.densityDpi == metrics.densityDpi
            }
            // Nova's resting composer uses the 20 dp horizontal inset.
            val nativeInset = (20f * density).roundToInt().coerceAtMost(windowWidth / 4)
            val left = reference?.left ?: nativeInset
            val right = reference?.right ?: nativeInset
            val width = (windowWidth - left - right).coerceAtLeast(1)
            val params = host.layoutParams as FrameLayout.LayoutParams
            val top = area.bottom - contentLocation[1] - height
            val geometryChanged = params.width != width || params.height != height ||
                params.leftMargin != left || params.topMargin != top
            if (geometryChanged) {
                params.width = width
                params.height = height
                params.leftMargin = left
                params.topMargin = top
                host.layoutParams = params
                ChatGptDiagnostics.report(anchor.context, "bottomBarBounds",
                    "left=${contentLocation[0] + left}; right=${contentLocation[0] + left + width}; height=$height; homeMeasured=${reference != null}")
            }
            if (now - lastBackdropCheck >= 300L) {
                lastBackdropCheck = now
                val ready = backdrop() != null
                if (!ready) ChatGptPageSource.bindNavigation(row, anchor, chrome)
                ChatGptDiagnostics.report(anchor.context, "bottomBarOptics",
                    "sceneReady=$ready; capture=${config.backdropCapture}; liquid=${config.liquidGlassEnabled}; blur=${config.blurRadius}; solid=${config.solidBarEnabled}")
            }
            if (now - lastSelectionCheck >= 350L) {
                lastSelectionCheck = now
                syncSelection()
            }
            val visible = area.bottom <= contentLocation[1] + content.height + 4 &&
                area.top >= contentLocation[1] + content.height * .65f
            if (visible && host.width == width && host.height == height && visibleGlyphs.isNotEmpty()) {
                setActive(true)
            } else if (!active) setActive(false)
            else awaitNative()
            true
        }

        private fun setActive(active: Boolean) {
            if (active == this.active) return
            this.active = active
            rowCapture.hideReplay(active)
            chrome.forEach {
                if (active) hiddenChrome[it] = this else hiddenChrome.remove(it)
                b.invalidateParent(it)
            }
            host.alpha = if (active) 1f else 0f
            host.isClickable = active
            ChatGptDiagnostics.report(anchor.context, "bottomBarRender", if (active) "shared renderer active" else "native")
            b.invalidateParent(row)
            anchor.invalidate()
        }

        fun syncSelection() {
            val provider = anchor.accessibilityNodeProvider ?: return
            val current = candidate.slots.mapNotNull { id -> provider.createAccessibilityNodeInfo(id) }
            val index = current.indexOfFirst { it.isSelected }
            if (current.size == candidate.slots.size && index >= 0 && current.count { it.isSelected } == 1 && selected != index) {
                commitSelection(index)
                host.invalidateNavigationSnapshot()
            }
        }

        private fun commitSelection(index: Int) {
            if (selected == index) return
            val changesExplore = selected == 2 || index == 2
            selected = index
            refreshStableArtwork()
            ChatGptPageSource.onPageChanged(row, anchor)
            if (changesExplore) ChatGptWindows.activity(anchor.context)?.let(ChatGptComposerGlass::requestScan)
        }

        fun tap(index: Int): Boolean {
            if (!active || !nativePlaced()) return false
            val id = candidate.slots.getOrNull(index) ?: return false
            val provider = anchor.accessibilityNodeProvider ?: return false
            val node = provider.createAccessibilityNodeInfo(id) ?: return false
            val layout = b.semanticLayout(anchor, id) ?: return false
            if (!b.contains(row, layout, onLimit = false) || !node.isEnabled) return false
            if (node.isSelected) return true
            if (!node.isClickable) return false
            return provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, Bundle.EMPTY).also { accepted ->
                ChatGptDiagnostics.report(anchor.context, "bottomBarTap", "slot=$index; accepted=$accepted")
                if (accepted) {
                    pendingSelection = index
                    probeUntil = SystemClock.uptimeMillis() + 650L
                    nextScan = 0L
                    anchor.postInvalidateOnAnimation()
                    anchor.post { syncSelection() }
                }
            }
        }

        fun dispose() {
            disposed = true
            if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnPreDrawListener(frames)
            content.removeCallbacks(expire)
            content.removeCallbacks(captureArtwork)
            stableArtwork.fill(null)
            unregisterNative()
            releaseRetiringRows()
            (glyphs + visibleGlyphs).distinct().forEach { it.drawing.clear() }
            rowCapture.clear()
            content.removeView(host)
            content.clipChildren = originalClipChildren
            content.clipToPadding = originalClipToPadding
        }
    }

    fun state(glassHost: GlassHostLayout): AppNavigationState = object : AppNavigationState {
        override val allowsSoftwareBackdropCapture = false
        override val suppliesRetainedNavigationBackdrop = true
        override fun retainedNavigationBackdrop() = byHost[glassHost]?.backdrop()
        override fun onBarGesture(phase: Int, host: View) { byHost[glassHost]?.onGesture(phase) }
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? = byHost[glassHost]?.selected
        override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean =
            byHost[host]?.tap(index) == true
        override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
            byHost[glassHost]?.drawNativeIcons(canvas, frame)
        }
        override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
        override fun suppressRedrawnSource() = false
        override fun forceNavigationBarImmersed(host: GlassHostLayout) = false
        override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = byHost.containsKey(host)
    }

}
