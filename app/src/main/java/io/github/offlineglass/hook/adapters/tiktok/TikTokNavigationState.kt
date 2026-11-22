package io.github.offlineglass.hook.adapters.tiktok

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.adapters.*

/** Native data is read on pre-draw; optical workers receive immutable artwork only. */
internal class TikTokNavigationState(private val host: io.github.offlineglass.hook.GlassHostLayout,
    private val mediaPageAllowed: (() -> Boolean)? = null,
    private val sampler: TikTokVideoSampler = TikTokVideoSampler(),
    private val ownsSampler: Boolean = true) : AppNavigationState {
    private data class Cell(val icon: Bitmap?, val label: String, val color: Int, val badge: String?, val dot: Boolean)
    private data class IconKey(val drawable: Drawable?, val selected: Boolean, val color: Int, val tint: Any?, val state: List<Int>)
    @Volatile private var cells: List<Cell> = emptyList()
    private val iconKeys = arrayOfNulls<IconKey>(5)
    private val icons = arrayOfNulls<Bitmap>(5)
    private var publishCenterX = .5f
    private var publishCenterY = .5f
    private val opticalCache = TikTokOpticalCache(host, ::videoBackdrop)
    private val backdropHold = TikTokBackdropHold()
    private var opticalWorker: android.os.HandlerThread? = null
    private var opticalHandler: android.os.Handler? = null
    override fun opticalSurfaceCopyHandler(): android.os.Handler = opticalHandler ?: run {
        val worker = android.os.HandlerThread("LiquidTab-TikTok-optics").apply { start() }
        opticalWorker = worker
        android.os.Handler(worker.looper).also { opticalHandler = it }
    }
    override fun shouldQueueOpticalRenderFromUi(animating: Boolean) = true
    private val pageLayout = TikTokPageLayout()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private var source: ViewGroup? = null
    private var selectionSource: ViewGroup? = null
    private var selectionReadTime = 0L
    private var selectionValue: Int? = null
    private var lastArtworkRead = 0L
    private var liveTexture: android.view.TextureView? = null
    private var sampledSurfaceTexture: android.graphics.SurfaceTexture? = null
    private var textureGapStarted = 0L
    private var waitingForTexture = false
    private val texturePosition = IntArray(2)
    private var textureLeft = Int.MIN_VALUE
    private var textureTop = Int.MIN_VALUE
    private var textureWidth = 0
    private var textureHeight = 0
    private val hostPosition = IntArray(2)
    private var sampledHostLeft = Int.MIN_VALUE
    private var sampledHostTop = Int.MIN_VALUE
    private var recordedTextureTimestamp = Long.MIN_VALUE
    private var compositorTimestamp = Long.MIN_VALUE
    private var blurWidth = 0
    private var blurHeight = 0
    private var blurEffect: RenderEffect? = null
    private var sceneEpoch = 0L
    private var sceneReady = false
    private var outerSceneEpoch = -1L
    private var blurSceneEpoch = -1L
    private var sceneRoot: ViewGroup? = null
    private var sceneDirty = true
    private var sceneCollectedAt = 0L
    private var sceneViews: List<View> = emptyList()
    private val sceneLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        sceneDirty = true
        if (ownsSampler && host.isShown) host.invalidate()
    }
    override fun onLiveBackdropRecorded(selectedIndex: Int) {
        // A wrapper recorded before the first native display list is usable
        // contains no live-node reference. Rebuild both derived caches when
        // that first valid scene arrives, even if their display lists exist.
        if (liveTexture == null || !sceneReady) sceneEpoch++
        sceneReady = true
        // Top panels have Session.framePulse; the bottom deliberately has no
        // continuous pump. Updating a sibling scene RenderNode does not dirty
        // this host's cached display list. Submit the newly committed input
        // to the bottom optics in this traversal, including the first valid
        // frame after an empty/transition background.
        if (ownsSampler && host.isAttachedToWindow && host.isShown) host.invalidate()
    }
    override val allowsSoftwareBackdropCapture = false
    override val pauseBackdropDuringBarExit = true
    // Discovering a Surface is not a completed capture. In particular live
    // preview source changes must not turn the existing GPU scene off while
    // the asynchronous first frame is still pending.
    // Bottom composites ordinary page pixels over any independent Surface
    // sample. A decoder sample must not disable its cover/photo scene.
    override fun needsSurfaceBackdrop() = !ownsSampler && liveTexture == null && videoBackdrop() != null
    override fun reuseOuterBackdropForSurface(surfaceActive: Boolean) = surfaceActive
    override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: Bitmap?, width: Int, height: Int, effect: RenderEffect?) =
        if (liveTexture != null) blurSceneEpoch != sceneEpoch || !prepared || width != blurWidth || height != blurHeight || effect !== blurEffect
        else blurSceneEpoch != sceneEpoch || opticalCache.blurNeedsRecord(prepared, backdrop, width, height, effect)
    override fun onOpticalBlurNodeRecorded(backdrop: Bitmap?, width: Int, height: Int, effect: RenderEffect?) {
        blurWidth = width; blurHeight = height; blurEffect = effect
        blurSceneEpoch = sceneEpoch
        opticalCache.recordBlur(backdrop, width, height, effect)
    }
    override fun onOpticalRendererConfigurationChanged() = opticalCache.clear()
    override fun skipLiveBackdrop(selectedIndex: Int) = needsSurfaceBackdrop()
    // A TextureView's buffer changes without changing its Bitmap epoch. Keep
    // its one-node GPU wrapper current; bitmap-based caches are fallback-only.
    override fun forceLiveBackdropFrame(selectedIndex: Int): Boolean {
        val texture = liveTexture ?: return false
        val timestamp = runCatching { texture.surfaceTexture?.timestamp }.getOrNull() ?: return false
        if (timestamp == compositorTimestamp) return false
        compositorTimestamp = timestamp
        return true
    }
    // Refresh for every new decoded buffer; duplicate UI frames need no replay.
    override fun canReuseLiveBackdrop(selectedIndex: Int): Boolean {
        val texture = liveTexture ?: return false
        val timestamp = runCatching { texture.surfaceTexture?.timestamp }.getOrNull() ?: return false
        val reuse = sceneReady && (!ownsSampler || !sceneDirty) && texture.isAvailable && timestamp == recordedTextureTimestamp
        recordedTextureTimestamp = timestamp
        return reuse
    }
    override fun updateBackdropRendererMode(host: io.github.offlineglass.hook.GlassHostLayout): Boolean {
        host.disableOpticalSurfaceNativeBlurForAdapter()
        // Photo RenderNodes are directly drawable; independent live Surfaces
        // already have the adapter's layer sampler. Neither needs root PixelCopy.
        host.disableOpticalSurfaceRendererForAdapter()
        return true
    }
    override fun onHostFrame(host: View) {
        val now = android.os.SystemClock.uptimeMillis()
        val allowed = mediaPageAllowed?.invoke() ?: (pageAllowsNavigation(host.rootView) == true)
        if (backdropHold.expire(host, now, allowed)) {
            sceneReady = false
            opticalCache.clear()
            this.host.clearLiveBackdropForAdapter()
            if (ownsSampler) this.host.invalidate()
        }
        // Native attachment precedes the first decoder buffer. An attached
        // empty texture must not replace a valid glass scene.
        val next = TikTokTextureHooks.active(host)?.takeIf { it.isAvailable &&
            runCatching { (it.surfaceTexture?.timestamp ?: 0L) > 0L }.getOrDefault(false) }
        waitingForTexture = next == null && liveTexture != null
        if (next == null && liveTexture != null) {
            if (textureGapStarted == 0L) textureGapStarted = now
            // Top panels retain their established behavior. The bottom must
            // start the incoming live sampler immediately, never stall it
            // behind a reference to the outgoing TextureView.
            if (!ownsSampler && allowed && now-textureGapStarted < 800L) return
            if (ownsSampler && allowed) backdropHold.capture(this.host, now)
        } else textureGapStarted = 0L
        next?.getLocationInWindow(texturePosition)
        host.getLocationInWindow(hostPosition)
        val nextSurface = next?.surfaceTexture
        if (next !== liveTexture || nextSurface !== sampledSurfaceTexture || (next != null &&
            (textureLeft != texturePosition[0] || textureTop != texturePosition[1] ||
                textureWidth != next.width || textureHeight != next.height ||
                sampledHostLeft != hostPosition[0] || sampledHostTop != hostPosition[1]))) {
            recordedTextureTimestamp = Long.MIN_VALUE
            compositorTimestamp = Long.MIN_VALUE
            liveTexture = next
            sampledSurfaceTexture = nextSurface
            textureLeft = texturePosition[0]; textureTop = texturePosition[1]
            textureWidth = next?.width ?: 0; textureHeight = next?.height ?: 0
            sampledHostLeft = hostPosition[0]; sampledHostTop = hostPosition[1]
            sceneReady = false
            if (ownsSampler) sceneDirty = true
            this.host.clearLiveBackdropForAdapter()
            opticalCache.clear()
            if (ownsSampler) this.host.invalidate()
        }
        if (next != null) {
            backdropHold.clear()
            if (ownsSampler && sampler.snapshot()!=null) sampler.dispose()
        } else sampler.request(host, allowed)
    }
    override fun retainBackdropDuringTransition(now: Long): Boolean =
        ((waitingForTexture && textureGapStarted != 0L && now-textureGapStarted < 800L) ||
            (liveTexture == null && sampler.awaitingFirstFrame(now))) &&
            (mediaPageAllowed?.invoke() ?: (pageAllowsNavigation(host.rootView) == true))
    override fun logDiagnostic(message: String) { android.util.Log.i("TikTokGlassDiag", message) }
    private fun call(view: Any?, name: String): Any? = view?.let {
        runCatching { XposedHelpers.callMethod(it, name) }.getOrNull()
    }
    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        source ?: return null
        val now = android.os.SystemClock.uptimeMillis()
        if (source === selectionSource && now-selectionReadTime < 8L) return selectionValue
        selectionSource = source; selectionReadTime = now
        selectionValue = (0 until minOf(slotCount, source.childCount)).firstOrNull { i ->
            i != 2 && (source.getChildAt(i).isSelected ||
                (call(source.getChildAt(i), "getTabTitleView") as? TextView)?.isSelected == true)
        }
        return selectionValue
    }
    override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float, barHeightPx: Int, surfaceColor: () -> Int) {
        this.source = source
        if (!enabled || source == null || source.childCount != 5) {
            TikTokInsets.setFeedStatus(host, false)
            return
        }
        val selectedIndex = resolveSelectedIndex(source, 5) ?: selected()
        TikTokInsets.setFeedStatus(host, selectedIndex in 0..1 && !pageLayout.overlayVisible(host.rootView))
        pageLayout.update(host, source, selectedIndex)
        val now = android.os.SystemClock.uptimeMillis()
        if (cells.isNotEmpty() && now-lastArtworkRead < 80L) return
        lastArtworkRead = now
        cells = (0 until 5).map { i ->
            val cell = source.getChildAt(i)
            val icon = call(cell, "getTabIcon") as? ImageView
            val title = call(cell, "getTabTitleView") as? TextView
            val color = title?.currentTextColor ?: Color.WHITE
            val drawable = icon?.drawable
            val key = IconKey(drawable, i == selectedIndex, color, icon?.colorFilter, drawable?.state?.toList().orEmpty())
            if (key != iconKeys[i] || icons[i] == null) {
                icons[i] = copyArtwork(drawable, icon?.colorFilter)
                // Publish has a dedicated coloured asset shared with the Douyin solution.
                if (i == 2 && icons[i] == null) icons[i] = runCatching {
                    host.context.createPackageContext("io.github.offlineglass", android.content.Context.CONTEXT_IGNORE_SECURITY)
                        .assets.open("tiktok/publish.png").use(BitmapFactory::decodeStream)
                }.getOrNull()
                if (i == 2) updatePublishCenter(icons[i])
                iconKeys[i] = key
            }
            val count = visibleBadgeCount(cell, title)
            val dot = call(cell, "getRedDotVIew") as? View
            Cell(icons[i], title?.text?.toString().orEmpty(), color,
                count.takeIf { it > 0 }?.let { if (it > 99) "99+" else it.toString() },
                dot != null && dot.visibility == View.VISIBLE && dot.alpha > .01f)
        }
    }
    private fun visibleBadgeCount(cell: View, title: TextView?): Int {
        val group = cell as? ViewGroup ?: return 0
        val stack = ArrayDeque<View>()
        stack.add(group)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 32) {
            val view = stack.removeLast()
            if (view is TextView && view !== title && view.isShown) {
                val value = view.text.toString().trim().removeSuffix("+").toIntOrNull()
                if (value != null && value > 0) return value
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
        }
        return 0
    }
    private fun updatePublishCenter(bitmap: Bitmap?) {
        publishCenterX = .5f; publishCenterY = .5f
        bitmap ?: return
        var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
        // Only when artwork changes; transparent native drawable padding is not
        // part of the visible publish glyph. Keep its existing rendered size.
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(bitmap.getPixel(x, y)) <= 8) continue
            left = minOf(left, x); right = maxOf(right, x)
            top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        if (right >= left && bottom >= top) {
            publishCenterX = (left + right + 1) / (2f * bitmap.width)
            publishCenterY = (top + bottom + 1) / (2f * bitmap.height)
        }
    }
    private fun copyArtwork(drawable: Drawable?, filter: ColorFilter?): Bitmap? {
        drawable ?: return null
        return runCatching {
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: return@runCatching null
            copy.state = drawable.state
            copy.level = drawable.level
            if (filter != null) copy.colorFilter = filter
            val h = 96
            val ratio = if (copy.intrinsicHeight > 0 && copy.intrinsicWidth > 0)
                copy.intrinsicWidth.toFloat() / copy.intrinsicHeight else 1f
            val w = (h * ratio).toInt().coerceIn(1, 288)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                copy.setBounds(0, 0, w, h)
                copy.draw(Canvas(it))
            }
        }.getOrNull()
    }
    override fun forceDarkMode(selectedIndex: Int): Boolean? {
        val color = cells.getOrNull(selectedIndex)?.color ?: return null
        return Color.red(color) + Color.green(color) + Color.blue(color) > 500
    }
    override fun pageAllowsNavigation(root: View?): Boolean? = source?.let { row ->
        // Source alpha is owned by the glass proxy; native visibility still controls pages.
        !pageLayout.overlayVisible(root) && allowsFeedPage(row, root) && row.isAttachedToWindow && row.isShown && row.width > 0 && row.height > 0
    }
    override fun hostPageAllowsNavigation(host: io.github.offlineglass.hook.GlassHostLayout, root: View?): Boolean =
        !pageLayout.overlayVisible(root) &&
            (host.adapterNavigationSource?.let { allowsFeedPage(it, root) && it.isAttachedToWindow && it.isShown && it.width > 0 && it.height > 0 } == true)

    override fun skipNavigationExitAnimation(root: View?): Boolean = TikTokAssistantVisibility.visible(root)

    private fun allowsFeedPage(row: ViewGroup, root: View?): Boolean {
        val selected = resolveSelectedIndex(row, 5)
        return selected != null && selected !in 0..1 || TikTokFeedVisibility.isVisible(root) != false
    }

    override val backdropSceneViewsAreDescendants = true
    override fun resolveBackdropScene(host: View): View? =
        host.rootView.findViewById<View>(android.R.id.content)

    /** Keep native page imagery and decoded textures in their actual draw order. */
    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
        // Preserve the accepted top-panel path. Bottom samples the complete
        // native page, including an incoming cover before its decoder starts.
        if (!ownsSampler) liveTexture?.let { return listOf(it) }
        if (ownsSampler) {
            if (sceneRoot !== content) {
                sceneRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(sceneLayoutListener)
                sceneRoot = content
                content.viewTreeObserver.addOnGlobalLayoutListener(sceneLayoutListener)
                sceneDirty = true
            }
            // Visibility-only native cover changes need not request layout.
            // Revalidate those at bounded cadence, never walk the tree for
            // every decoded frame. Source changes invalidate immediately.
            if (!sceneDirty && android.os.SystemClock.uptimeMillis()-sceneCollectedAt < 250L) return sceneViews
        }
        val safe = ArrayList<View>()
        var visited = 0
        fun collect(view: View, depth: Int): Boolean {
            if (++visited > 8000 || depth > 32) return true
            if (view is io.github.offlineglass.hook.GlassHostLayout || view === source ||
                view is AdapterOwnedOverlay) return true
            // Hidden cached feed/player pages draw nothing. They must not force
            // an otherwise safe inbox/profile subtree into foreground-only
            // leaves, which drops its own opaque background from the GPU scene.
            if (view.visibility != View.VISIBLE || view.alpha <= .001f) return false
            // A SurfaceView's node clears a compositor hole; its pixels come
            // from the independent sampler. Never replay that CLEAR operation.
            if (view is android.view.SurfaceView) return true
            if (view is android.view.TextureView) {
                if (ownsSampler && TikTokTextureHooks.registered(view) && view.isAvailable &&
                    runCatching { (view.surfaceTexture?.timestamp ?: 0L) > 0L }.getOrDefault(false)) safe += view
                return true
            }
            val start = safe.size
            // Safety is a property of the current visible tree. Never retain a
            // prior page's split decision after its player becomes hidden.
            var split = false
            if (view is ViewGroup) for (i in 0 until view.childCount)
                split = collect(view.getChildAt(i), depth+1) || split
            if (!split) {
                while (safe.size > start) safe.removeAt(safe.lastIndex)
                safe += view
            }
            return split
        }
        for (i in 0 until hostIndex) collect(content.getChildAt(i), 0)
        if (ownsSampler) {
            sceneViews = safe
            sceneDirty = false
            sceneCollectedAt = android.os.SystemClock.uptimeMillis()
        }
        return safe
    }
    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = synchronized(this) {
        with(frame) {
            val snapshot = cells
            text.textSize = textSize * scaledDensity
            barLayout?.let { layout ->
                if (!hideSelectedForOptics) {
                    val cx = layout.actionLeft + layout.actionSize / 2f
                    val cy = height / 2f
                    // The shared local-action surface owns blur/refraction;
                    // this foreground contributes only the fixed white plus.
                    actionPaint.style = Paint.Style.STROKE
                    actionPaint.color = Color.WHITE
                    actionPaint.strokeWidth = 2.6f * density
                    val half = minOf(9f * density, layout.actionSize * .17f)
                    canvas.drawLine(cx-half, cy, cx+half, cy, actionPaint)
                    canvas.drawLine(cx, cy-half, cx, cy+half, actionPaint)
                }
            }
            for ((visualIndex, i) in enabledIndices.withIndex()) {
                if (i !in snapshot.indices) continue
                if (barLayout?.actionIndex == i) continue
                val cell = snapshot[i]
                val x = 4f * density + (visualIndex + .5f) * slotWidth
                val save = canvas.save()
                canvas.scale(extraScale, extraScale, x, height / 2f)
                val originalIconHeight = 27f * density * iconScale
                val iconHeight = (if (i == 2) 40f * density * iconScale else originalIconHeight * 1.32f)
                val metrics = text.fontMetrics
                // Preserve the old edge-to-text gap, while centering the whole
                // enlarged icon/text block instead of two fixed percentages.
                val gap = (height * .78f + metrics.ascent - (height * .32f + originalIconHeight / 2f)) * .5f
                val blockTop = (height - iconHeight - gap - (metrics.descent - metrics.ascent)) / 2f
                val iconY = if (i == 2 || iconOnly) height / 2f else blockTop + iconHeight / 2f
                val baseline = blockTop + iconHeight + gap - metrics.ascent
                cell.icon?.let { bitmap ->
                    val h = iconHeight
                    val w = h * bitmap.width / bitmap.height
                    val left = x - w * (if (i == 2) publishCenterX else .5f)
                    val top = iconY - h * (if (i == 2) publishCenterY else .5f)
                    paint.colorFilter = null
                    paint.alpha = 255
                    canvas.drawBitmap(bitmap, null, RectF(left, top, left+w, top+h), paint)
                }
                if (i != 2 && !iconOnly) {
                    text.color = cell.color
                    canvas.drawText(frame.localizeNavigationLabel(cell.label), x, baseline, text)
                }
                if (cell.badge != null || cell.dot) {
                    val bx = x + 13f*density
                    val by = height * .2f
                    paint.color = Color.rgb(254, 44, 85)
                    val radius = (if (cell.badge == null) 3f else 8f)*density
                    val bw = if (cell.badge == null) radius else maxOf(radius, cell.badge.length*3.5f*density)
                    canvas.drawRoundRect(RectF(bx-bw, by-radius, bx+bw, by+radius), radius, radius, paint)
                    cell.badge?.let {
                        text.color = Color.WHITE
                        text.textSize = 10f*scaledDensity
                        canvas.drawText(it, bx, by-(text.fontMetrics.ascent+text.fontMetrics.descent)/2, text)
                        text.textSize = textSize*scaledDensity
                    }
                }
                canvas.restoreToCount(save)
            }
        }
    }
    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame, selectionPath: Path, drawSource: () -> Unit): Boolean {
        // Same optical mix as Douyin: one foreground outside the lens, residual inside.
        val mix = frame.navigationOpticalMix
        if (mix <= 0 || selectionPath.isEmpty) { drawSource(); return true }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), ((1-mix)*255).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }
    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
        if (!ownsSampler || liveTexture == null) sampler.request(host, pageAllowed)
        if (!pageAllowed) backdropHold.clear()
    }
    override fun videoBackdrop(): AppVideoBackdrop? = sampler.snapshot() ?: backdropHold.frame
    override fun outerBackdropNeedsRecord(backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean) =
        if (liveTexture != null) outerSceneEpoch != sceneEpoch || !hasDisplayList
        else outerSceneEpoch != sceneEpoch || opticalCache.outerNeedsRecord(backdrop, width, height, hasDisplayList)
    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerSceneEpoch = sceneEpoch
        opticalCache.recordOuter(backdrop, width, height)
    }
    override fun onHostDetached() {
        sceneRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(sceneLayoutListener)
        sceneRoot = null
        sceneViews = emptyList()
        sceneDirty = true
        liveTexture = null
        sampledSurfaceTexture = null
        sceneReady = false
        backdropHold.clear()
        if (ownsSampler) sampler.dispose()
        opticalWorker?.quitSafely()
        opticalWorker = null; opticalHandler = null
        opticalCache.clear()
        pageLayout.dispose()
        source = null
        cells = emptyList()
        icons.fill(null); iconKeys.fill(null)
    }
}
