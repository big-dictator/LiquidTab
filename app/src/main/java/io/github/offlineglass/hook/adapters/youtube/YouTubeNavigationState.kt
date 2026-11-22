package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
import io.github.offlineglass.hook.adapters.localizeNavigationLabel
import kotlin.math.min
import java.util.WeakHashMap

/** Owns YouTube's Shorts visibility rules and tab badge artwork for one glass host. */
internal class YouTubeNavigationState(
    private val host: GlassHostLayout,
) : AppNavigationState {
    // Shorts avatars may use hardware BitmapShaders. Software drawing the
    // parent tree throws inside YouTube/Litho as new clips become visible.
    // Native pages use the live scene; decoder pages use videoSampler instead.
    override val allowsSoftwareBackdropCapture = false
    private var badgeRects: List<RectF?> = emptyList()
    private var shortsPresenter: View? = null
    private var shortsActive = false
    private var lastShortsScan = 0L
    private var shortsLastSeen = 0L
    private var shortsRecycler: View? = null
    private val recyclerClassCache = HashMap<String, Boolean>()
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val shortsLayout = YouTubeShortsLayout()
    private val searchLayout = YouTubeSearchLayout()
    private val videoSampler = YouTubeVideoSampler()
    private val tabsRenderer = YouTubeTabsRenderer()
    private val bottomGradient = YouTubeBottomGradient(host)
    private val opticalHandler = Handler(Looper.getMainLooper())
    // Native tab Views are painted live; keep their drawing on the UI thread.
    override fun opticalSurfaceCopyHandler(): Handler = opticalHandler
    override fun deferOpticalCopyRenderToUi(): Boolean = true
    private var contentMovingUntil = 0L
    private var observedPage = -1
    private var capturePageEpoch = 0L
    override fun contentMotionHoldUntil(): Long = contentMovingUntil
    override fun opticalCaptureEpoch(): Long = capturePageEpoch

    override fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean {
        // The native centre action opens publishing; it is not a feed page.
        if (slotCount != 5 || index !in 0 until slotCount || index == 2) return false
        contentMovingUntil = maxOf(contentMovingUntil, now + 240L)
        surfaceDirty = true
        // The shared host resets only its idle copy backoff, once per tap.
        return true
    }

    override fun acceptNativeNavigationSelection(index: Int, now: Long): Boolean? {
        if (index in 0..4 && index != 2 && index != observedPage) {
            observedPage = index
            capturePageEpoch++
            surfaceDirty = true
            contentMovingUntil = maxOf(contentMovingUntil, now + 120L)
            videoSampler.invalidatePage()
        }
        return null // Native selection continues to own the page state.
    }
    private var surfaceDirty = true
    private var renderedVideoEpoch = -1
    private var wasAnimating = false
    private var artworkRefreshAt = 0L

    override fun onOpticalBackdropChanged() { surfaceDirty = true }
    override fun onOpticalRendererConfigurationChanged() { surfaceDirty = true }
    override fun onOpticalSurfaceFrameInvalidated() { surfaceDirty = true }
    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? {
        if (!host.hasWindowFocus() || host.windowVisibility != View.VISIBLE) return false
        val draw = surfaceDirty || animating || wasAnimating ||
            (videoSampler.frame(host)?.epoch ?: -1) != renderedVideoEpoch ||
            SystemClock.uptimeMillis() >= artworkRefreshAt
        wasAnimating = animating
        return if (draw) null else false
    }
    override fun onOpticalSurfaceFrameRendered(): Boolean {
        surfaceDirty = false
        renderedVideoEpoch = videoSampler.frame(host)?.epoch ?: -1
        artworkRefreshAt = SystemClock.uptimeMillis() + 250L
        return false
    }
    private val splitSceneBranches = WeakHashMap<View, Boolean>()
    private var outerRecordedShorts: Boolean? = null

    override fun logDiagnostic(message: String) = android.util.Log.i("YouTubeGlassDiag", message).let { }

    private var pivot: YouTubePivotBinding? = null
    private var pivotRetryAt = 0L
    private var lastPivotProbe: ViewGroup? = null

    private fun bindPivot(source: ViewGroup? = host.adapterNavigationSource): YouTubePivotBinding? {
        if (source == null) { pivot = null; return null }
        pivot?.takeIf { it.matches(source) }?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (lastPivotProbe === source && pivot == null && now < pivotRetryAt) return null
        lastPivotProbe = source
        pivotRetryAt = now + 250L
        return YouTubePivotBinding.resolve(source).also { pivot = it }
    }

    override fun isNavigationEntryAvailable(index: Int): Boolean = bindPivot()?.entry(index) != null

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int =
        bindPivot(source)?.selected() ?: host.adapterSelectedIndex

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        // Revalidate the current cell against its role before dispatching.
        val verified = source?.let(YouTubePivotBinding::resolve) ?: return false
        pivot = verified
        val target = verified.entry(index)?.cell ?: return false
        if (!target.isAttachedToWindow || !target.isEnabled || !target.hasOnClickListeners()) return false
        if (!target.performClick()) return false
        onNavigationSelectionRequested(index, slotCount, SystemClock.uptimeMillis())
        return true
    }

    // Ordinary pages use existing RenderNodes. Only an overlapping decoder
    // snapshot owns the independent Surface, avoiding two optical outputs.
    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = videoBackdrop() != null

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        val videoReady = canDrawNavigation() && videoBackdrop() != null
        host.disableOpticalSurfaceNativeBlurForAdapter()
        if (videoReady) host.enableOpticalSurfaceRendererForAdapter()
        else host.disableOpticalSurfaceRendererForAdapter()
        return true
    }

    override fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float): Boolean =
        canDrawNavigation() && hostVisibility == View.VISIBLE && hostAlpha > 0.001f

    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
        if (!host.hasWindowFocus() || host.windowVisibility != View.VISIBLE) { videoSampler.pause(); return }
        videoSampler.request(this.host, pageAllowed && canDrawNavigation())
    }

    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.frame(host)

    override fun onHostFrame(host: View) {
        bottomGradient.update(canDrawNavigation())
        searchLayout.update(host.rootView, YouTubeSearchPage.active(host.rootView))
        // Resume the existing full-rate probe on a native page redraw rather
        // than increasing the sampling rate of genuinely idle pages.
        if (host.rootView.isDirty) contentMovingUntil = SystemClock.uptimeMillis() + 120L
        updateBackdropRendererMode(this.host)
    }

    override fun onHostFocusLost() {
        videoSampler.pause()
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) { surfaceDirty = true; host.postInvalidateOnAnimation() }
    }

    override fun syncVisibilityCompanion(host: View, selectedIndex: Int): Boolean {
        bottomGradient.update(canDrawNavigation())
        return false // Keep the shared optical Surface synchronization.
    }

    override fun pauseBackdropWork(host: View): Boolean =
        !host.hasWindowFocus() || host.windowVisibility != View.VISIBLE

    private fun canDrawNavigation(): Boolean {
        if (YouTubeSearchPage.active(host.rootView)) return false
        var context: Context? = host.context
        while (context is ContextWrapper) {
            if (context is Activity) return !context.isInPictureInPictureMode
            val next = context.baseContext
            if (next === context) break
            context = next
        }
        return true
    }

    override fun beforeHostDraw(host: View, source: ViewGroup?): Boolean = canDrawNavigation()
    override fun drawsNavigationContent(): Boolean = canDrawNavigation()
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean = true
    override fun navigationOpticalMixEnabled(): Boolean = host.adapterNavigationAnimationRunning
    override fun drawForeground(
        canvas: Canvas,
        frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path,
        drawSource: () -> Unit,
    ): Boolean {
        if (frame.navigationOpticalMix <= 0f || selectionPath.isEmpty || !host.adapterNavigationAnimationRunning) {
            drawSource()
            return true
        }
        fun drawHalf(inside: Boolean, alpha: Float = 1f) {
            val save = canvas.save()
            if (inside) canvas.clipPath(selectionPath) else canvas.clipOutPath(selectionPath)
            tabsRenderer.opticalSelected = inside
            val layer = if (alpha < 0.999f) canvas.saveLayerAlpha(
                0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                (alpha * 255f).toInt().coerceIn(0, 255),
            ) else -1
            try { drawSource() } finally {
                if (layer >= 0) canvas.restoreToCount(layer)
                tabsRenderer.opticalSelected = null
                canvas.restoreToCount(save)
            }
        }
        drawHalf(inside = false)
        val residual = (1f - frame.navigationOpticalMix).coerceIn(0f, 1f)
        if (residual > 0f) drawHalf(inside = true, alpha = residual)
        return true
    }
    override fun hostChromeAlpha(host: View, selectedIndex: Int): Float =
        if (canDrawNavigation()) 1f else 0f
    override fun allowsHostTouch(): Boolean =
        host.hasWindowFocus() && nativeBottomVisible() && !YouTubeDrawerPage.active(host.rootView)

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (!canDrawNavigation()) return
        val binding = bindPivot()
        val scale = frame.iconScale * frame.extraScale
        frame.enabledIndices.forEachIndexed { visual, index ->
            val cx = 4f * frame.density + (visual + .5f) * frame.slotWidth
            val cy = frame.height / 2f
            val optical = tabsRenderer.opticalSelected ?: if (host.drawingNavigationForIndicatorOptics)
                host.isNavigationSlotInsideIndicator(cx, cy) else null
            val drawn = binding != null && tabsRenderer.drawSlot(canvas, binding,
                index, cx, cy, scale, host.config, optical, frame.darkGlass,
                frame.iconOnly, frame::localizeNavigationLabel)
            if (!drawn) {
                val iconY = if (frame.iconOnly) cy else cy - 8f * frame.density
                drawFallbackGlyph(canvas, index, cx, iconY, 44f * frame.density * scale, frame.darkGlass)
                if (!frame.iconOnly) {
                    val paint = badgePaint.apply {
                        color = if (frame.darkGlass) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                        style = Paint.Style.FILL; textAlign = Paint.Align.CENTER
                        textSize = frame.textSize * frame.scaledDensity
                    }
                    canvas.drawText(frame.localizeNavigationLabel(listOf("首页", "Shorts", "发布", "订阅", "我").getOrElse(index) { "" }),
                        cx, cy + 19f * frame.density, paint)
                }
            }
        }
    }

    override fun drawFixedNavigationSlot(
        canvas: Canvas, index: Int, selectedIndex: Int, dark: Boolean,
        centerX: Float, centerY: Float, boxSize: Float,
    ): Boolean {
        val binding = bindPivot() ?: return true
        val source = binding.source
        val scale = boxSize / min(source.width / host.adapterSlotCount.toFloat(), source.height.toFloat()).coerceAtLeast(1f)
        val opticalState = tabsRenderer.opticalSelected ?: if (host.drawingNavigationForIndicatorOptics) {
            host.isNavigationSlotInsideIndicator(centerX, centerY)
        } else null
        val drawn = canDrawNavigation() && tabsRenderer.drawSlot(canvas, binding,
            index, centerX, host.height / 2f, scale, host.config, opticalState, dark,
            host.config.iconOnly || host.config.bottomBarIconOnly,
            { label ->
                if (host.config.managerEnglishNavigationLabels)
                    io.github.offlineglass.hook.adapters.NavigationLabelLocalization.toEnglish("youtube", label)
                else label
            })
        if (!drawn) drawFallbackGlyph(canvas, index, centerX, host.height / 2f, boxSize, dark)
        return true
    }

    override fun drawAppBadgeOverlay(canvas: Canvas, host: View, contentScale: Float) {
        if (!canDrawNavigation()) return
        if (!isNavigationEntryAvailable(2)) return
        val action = YouTubeAdapter.barLayout(this.host.config, host.width, host.height,
            host.resources.displayMetrics.density, 5) ?: return
        drawPublishGlyph(canvas, action.actionLeft + action.actionSize / 2f,
            host.height / 2f, action.actionSize, false)
    }

    private fun drawPublishGlyph(canvas: Canvas, cx: Float, cy: Float, box: Float, dark: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (box * .045f).coerceAtLeast(1.5f)
            strokeCap = Paint.Cap.ROUND
        }
        val arm = box * .18f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, paint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, paint)
    }

    private fun drawFallbackGlyph(canvas: Canvas, index: Int, cx: Float, cy: Float, box: Float, dark: Boolean) {
        val size = (box * .28f).coerceAtLeast(9f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            style = Paint.Style.STROKE; strokeWidth = (box * .055f).coerceAtLeast(1.6f)
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        when (index) {
            0 -> {
                val path = Path().apply {
                    moveTo(cx-size, cy-size*.05f); lineTo(cx, cy-size); lineTo(cx+size, cy-size*.05f)
                    lineTo(cx+size*.72f, cy-size*.05f); lineTo(cx+size*.72f, cy+size)
                    lineTo(cx+size*.2f, cy+size); lineTo(cx+size*.2f, cy+size*.32f)
                    lineTo(cx-size*.2f, cy+size*.32f); lineTo(cx-size*.2f, cy+size)
                    lineTo(cx-size*.72f, cy+size); lineTo(cx-size*.72f, cy-size*.05f); close()
                }
                paint.style = Paint.Style.FILL; canvas.drawPath(path, paint)
            }
            1 -> {
                val path = Path().apply {
                    moveTo(cx-size*.7f, cy-size); cubicTo(cx+size*.6f, cy-size*1.2f, cx-size*.7f, cy-size*.1f, cx+size*.65f, cy-size*.08f)
                    cubicTo(cx-size*.45f, cy+size*.1f, cx+size*.7f, cy+size*1.15f, cx-size*.65f, cy+size)
                }
                canvas.drawPath(path, paint)
            }
            3 -> {
                val r = size*.8f
                canvas.drawRect(cx-r, cy-r*.7f, cx+r, cy+r*.7f, paint)
                val play = Path().apply { moveTo(cx-size*.22f, cy-size*.38f); lineTo(cx+size*.43f, cy); lineTo(cx-size*.22f, cy+size*.38f); close() }
                paint.style = Paint.Style.FILL; canvas.drawPath(play, paint)
            }
            4 -> {
                canvas.drawCircle(cx, cy-size*.35f, size*.42f, paint)
                canvas.drawArc(cx-size, cy-size*.05f, cx+size, cy+size*1.2f, 185f, 170f, false, paint)
            }
        }
    }

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
        val safe = ArrayList<View>()
        // Never retain a RenderNode whose descendants include a SurfaceView
        // compositor hole or a TextureView external texture. Split such a
        // branch into safe siblings, retaining Android's native draw order.
        fun collect(view: View): Boolean {
            if (view === host || view === host.adapterNavigationSource ||
                view is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay ||
                view is GlassHostLayout) return true
            if (view.visibility != View.VISIBLE || view.alpha <= 0.001f) return false
            if (view is SurfaceView || view is TextureView) return true
            val start = safe.size
            // A removed player can still be in the preceding display list.
            // Once split, never replay that ancestor as a whole again.
            var split = splitSceneBranches.containsKey(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) split = collect(view.getChildAt(index)) || split
            }
            if (!split) {
                while (safe.size > start) safe.removeAt(safe.lastIndex)
                safe += view
            } else if (view is ViewGroup) {
                splitSceneBranches[view] = true
            }
            return split
        }
        for (index in 0 until hostIndex) collect(content.getChildAt(index))
        return safe
    }

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
        density: Float, barHeightPx: Int, surfaceColor: () -> Int,
    ) {
        shortsLayout.update(this.host, enabled && isShortsActive(), shortsRecycler)
    }

    private val nativeBottomBounds = Rect()
    private fun nativeBottomVisible(): Boolean {
        val source = host.adapterNavigationSource ?: return false
        // YouTube may leave the PivotBar visible with just a few pixels of
        // inset while its actual row and all tab cells have collapsed to zero.
        return source.isAttachedToWindow && bindPivot(source)?.hasVisibleCell(nativeBottomBounds) == true
    }

    // Source alpha is deliberately suppressed by the host. Follow native
    // visibility and clipping without using that projection alpha as a signal.
    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean = nativeBottomVisible()
    override fun forceNavigationBarImmersed(host: GlassHostLayout): Boolean? =
        if (YouTubeSearchPage.active(host.rootView)) false else null

    override fun suppressNativeChromeEarly(source: ViewGroup?) = suppressNativeChrome(source)

    override fun suppressNativeChrome(source: ViewGroup?) {
        source ?: return
        source.background = null
        source.backgroundTintList = null
        source.isHorizontalFadingEdgeEnabled = false
        source.isHorizontalScrollBarEnabled = false
        (source.parent as? ViewGroup)?.let { shell ->
            shell.background = null
            shell.backgroundTintList = null
        }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += source to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view !== source && (view is android.widget.Button || view.isClickable)) {
                view.background = null
                view.backgroundTintList = null
                view.foreground = null
            }
            if (view is ViewGroup && depth < 5) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
    }

    override fun transformNavigationSnapshot(
        source: ViewGroup,
        snapshot: Bitmap,
        slotCount: Int,
    ): Bitmap? {
        val content = (0 until source.childCount).asSequence()
            .map(source::getChildAt)
            .firstOrNull { it.width >= source.width * 0.9f && it.height in 1 until source.height }
            ?: return null
        if (snapshot.height <= content.height || snapshot.width != content.width) return null
        val top = content.top.coerceIn(0, snapshot.height - content.height)
        return runCatching { Bitmap.createBitmap(snapshot, 0, top, snapshot.width, content.height) }
            .onSuccess {
                XposedBridge.log(
                    "[OfflineGlass][YouTubeDiag] trim snapshot ${snapshot.height}->${it.height} " +
                        "row=${source.height} content=${content.height}",
                )
            }
            .getOrNull()
    }

    override fun onNavigationSnapshotReady(snapshot: Bitmap, slotCount: Int) {
        if (snapshot.isRecycled || snapshot.width <= 0 || snapshot.height <= 0 || slotCount <= 0) {
            badgeRects = emptyList()
            return
        }
        runCatching {
            val width = snapshot.width
            val height = snapshot.height
            val pixels = IntArray(width * height)
            snapshot.getPixels(pixels, 0, width, 0, 0, width, height)
            val slotWidth = width.toFloat() / slotCount
            badgeRects = (0 until slotCount).map { slot ->
                val left = (slot * slotWidth).toInt().coerceIn(0, width - 1)
                val right = ((slot + 1) * slotWidth).toInt().coerceIn(left + 1, width)
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var maxX = -1
                var maxY = -1
                for (y in 0 until height) {
                    val rowOffset = y * width
                    for (x in left until right) {
                        val pixel = pixels[rowOffset + x]
                        if (pixel ushr 24 < 200) continue
                        if ((pixel ushr 16 and 0xFF) >= 170 &&
                            (pixel ushr 8 and 0xFF) <= 110 && (pixel and 0xFF) <= 110
                        ) {
                            if (x < minX) minX = x
                            if (y < minY) minY = y
                            if (x > maxX) maxX = x
                            if (y > maxY) maxY = y
                        }
                    }
                }
                if (maxX < minX || maxY < minY) null
                else RectF(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f)
            }
        }.onFailure { error ->
            badgeRects = emptyList()
            XposedBridge.log("[OfflineGlass][YouTubeDiag] badge scan failed: $error")
        }
    }

    override fun drawNavigationSnapshotOverlay(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        snapshot: Bitmap?,
    ) {
        val bitmap = snapshot?.takeUnless(Bitmap::isRecycled) ?: return
        if (frame.width <= 0 || frame.height <= 0 || badgeRects.isEmpty()) return
        val sourceWidth = bitmap.width.toFloat()
        val sourceHeight = bitmap.height.toFloat()
        val nativeSlotWidth = sourceWidth / frame.slotCount.coerceAtLeast(1)
        val slotWidth = frame.slotWidth
        val pad = 4f * frame.density
        for ((visualIndex, index) in frame.enabledIndices.withIndex()) {
            val badge = badgeRects.getOrNull(index) ?: continue
            val scale = min(slotWidth / nativeSlotWidth, frame.height / sourceHeight).coerceAtLeast(0.0001f)
            val sourceCenterX = (index + 0.5f) * nativeSlotWidth
            val targetCenterX = pad + (visualIndex + 0.5f) * slotWidth
            val dx = targetCenterX - sourceCenterX * scale
            val dy = (frame.height - sourceHeight * scale) / 2f
            val left = pad + visualIndex * slotWidth
            val right = left + slotWidth
            val centerX = (left + right) / 2f
            canvas.save()
            canvas.clipRect(left, 0f, right, frame.height.toFloat())
            canvas.scale(frame.extraScale, frame.extraScale, centerX, frame.height / 2f)
            canvas.translate(dx, dy)
            canvas.scale(scale, scale)
            val src = Rect(badge.left.toInt(), badge.top.toInt(), badge.right.toInt(), badge.bottom.toInt())
            canvas.drawBitmap(bitmap, src, badge, badgePaint)
            canvas.restore()
        }
    }

    override fun onHostDetached() {
        bottomGradient.release()
        pivot = null
        tabsRenderer.clear()
        shortsLayout.restore()
        videoSampler.dispose()
        resetShortsState()
    }

    override fun dispose() {
        bottomGradient.release()
        pivot = null
        tabsRenderer.clear()
        shortsLayout.restore()
        videoSampler.dispose()
        resetShortsState()
        badgeRects = emptyList()
        recyclerClassCache.clear()
        splitSceneBranches.clear()
    }

    private fun pivotBarSelectsShorts(): Boolean? {
        return bindPivot()?.selected()?.let { it == 1 }
    }

    private fun isShortsActive(): Boolean {
        val now = SystemClock.uptimeMillis()
        // The committed tab is authoritative even if the retained native row
        // is hidden and an old ReelRecyclerView still reports isShown.
        if (host.adapterSelectedIndex != 1) {
            if (shortsActive) {
                XposedBridge.log("[OfflineGlass][YTBar] shorts EXIT pivot t=$now")
                leaveShorts()
            }
            return false
        }
        val reel = shortsRecycler
        if (reel != null && reel.isAttachedToWindow && reel.isShown && reel.width > 0) {
            shortsLastSeen = now
            return true
        }
        if (now - lastShortsScan < SHORTS_SCAN_INTERVAL_MS) {
            return now - shortsLastSeen < SHORTS_EXIT_GRACE_MS
        }
        lastShortsScan = now
        val root = host.rootView as? ViewGroup
        val presenter = root?.let(::findShortsProgressPresenter)
        shortsPresenter = presenter
        val foundReel = root?.let(::findShortsRecyclerView)
        val present = foundReel != null || (presenter != null && presenter.isShown && presenter.width > 0)
        if (present) {
            shortsLastSeen = now
            shortsActive = true
            if (foundReel != null) {
                shortsRecycler = foundReel
            }
            return true
        }
        if (now - shortsLastSeen < SHORTS_EXIT_GRACE_MS) return true
        if (shortsActive) {
            XposedBridge.log("[OfflineGlass][YTBar] shorts EXIT t=$now")
            leaveShorts()
        }
        return false
    }

    private fun leaveShorts() {
        shortsActive = false
        shortsRecycler = null
        shortsPresenter = null
        shortsLastSeen = 0L
        lastShortsScan = 0L
        videoSampler.dispose()
        shortsLayout.restore()
        outerRecordedShorts = null
        // Clear the old scene signature so Home/subscriptions record their
        // own live scene on this traversal; software capture stays forbidden.
        host.clearLiveBackdropForAdapter()
        host.postInvalidateOnAnimation()
    }

    private fun isRecyclerView(view: View): Boolean {
        val concrete = view.javaClass.name
        recyclerClassCache[concrete]?.let { return it }
        var result = false
        var cls: Class<*>? = view.javaClass.superclass
        var hops = 0
        while (cls != null && hops < 8) {
            if (cls.name.contains("RecyclerView", ignoreCase = true) ||
                cls.methods.any { it.name == "addOnScrollListener" && it.parameterTypes.size == 1 }
            ) {
                result = true
                break
            }
            cls = cls.superclass
            hops++
        }
        recyclerClassCache[concrete] = result
        return result
    }

    private fun findShortsRecyclerView(root: ViewGroup): View? {
        var best: View? = null
        var bestHeight = 0
        val presenterReady = shortsPresenter?.isShown == true
        val rootWidth = root.width
        val rootHeight = root.height
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view !== host && view.width > 0 && view.height > 0 && view.isShown) {
                if (view.javaClass.name.contains("ReelRecyclerView")) return view
                if (presenterReady && view.width >= rootWidth * 0.75f && view.height >= rootHeight * 0.5f &&
                    isRecyclerView(view) && view.height > bestHeight
                ) {
                    bestHeight = view.height
                    best = view
                }
            }
            if (view is ViewGroup && depth < 24) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return best
    }

    private fun findShortsProgressPresenter(root: ViewGroup): View? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view !== host && view.width > 0 && view.javaClass.name.endsWith(SHORTS_PROGRESS_CLASS)) return view
            if (view is ViewGroup && depth < 40) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return null
    }

    private fun resetShortsState() {
        shortsActive = false
        shortsRecycler = null
        shortsPresenter = null
    }

    private companion object {
        const val SHORTS_SCAN_INTERVAL_MS = 96L
        const val SHORTS_EXIT_GRACE_MS = 800L
        const val SHORTS_PROGRESS_CLASS = "ShortsPlayerProgressPresenter"
    }
}
