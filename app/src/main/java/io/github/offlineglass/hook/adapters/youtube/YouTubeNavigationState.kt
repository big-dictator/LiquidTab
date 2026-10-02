package io.github.offlineglass.hook.adapters.youtube

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import de.robv.android.xposed.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AppVideoBackdrop
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
    private val videoSampler = YouTubeVideoSampler()
    private val tabsRenderer = YouTubeTabsRenderer()
    private val opticalCache = YouTubeOpticalCache(host, videoSampler)
    private val opticalHandler = Handler(Looper.getMainLooper())
    // Native tab Views are painted live; keep their drawing on the UI thread.
    override fun opticalSurfaceCopyHandler(): Handler = opticalHandler
    private val splitSceneBranches = WeakHashMap<View, Boolean>()
    private var outerRecordedShorts: Boolean? = null

    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean = opticalCache.outerNeedsRecord(backdrop, width, height, hasDisplayList)

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        opticalCache.recordOuter(backdrop, width, height)
    }

    override fun reuseOuterBackdropForSurface(surfaceActive: Boolean): Boolean = surfaceActive
    override fun opticalBlurNodeNeedsRecord(
        prepared: Boolean, backdrop: Bitmap?, width: Int, height: Int,
        effect: android.graphics.RenderEffect?,
    ): Boolean = opticalCache.blurNeedsRecord(prepared, backdrop, width, height, effect)

    override fun onOpticalBlurNodeRecorded(
        backdrop: Bitmap?, width: Int, height: Int, effect: android.graphics.RenderEffect?,
    ) = opticalCache.recordBlur(backdrop, width, height, effect)

    override fun onOpticalRendererConfigurationChanged() = opticalCache.clear()

    override fun logDiagnostic(message: String) = android.util.Log.i("YouTubeGlassDiag", message).let { }

    // A SurfaceView display list contains a compositor hole, not video pixels.
    // Replaying it beneath an animated bar can move that hole with the bar.
    override fun skipLiveBackdrop(selectedIndex: Int): Boolean =
        true

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        if (host.liveBackdropActiveForAdapter) host.clearLiveBackdropForAdapter()
        host.disableOpticalSurfaceNativeBlurForAdapter()
        if (canDrawNavigation()) host.enableOpticalSurfaceRendererForAdapter()
        else host.disableOpticalSurfaceRendererForAdapter()
        return true
    }

    override fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float): Boolean =
        canDrawNavigation() && hostVisibility == View.VISIBLE && hostAlpha > 0.001f

    override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
        videoSampler.request(this.host, pageAllowed && canDrawNavigation())
    }

    override fun videoBackdrop(): AppVideoBackdrop? = videoSampler.snapshot

    override fun onHostFrame(host: View) {
        updateBackdropRendererMode(this.host)
    }

    override fun onHostFocusLost() {
        opticalCache.clear()
        videoSampler.dispose()
        host.clearLiveBackdropForAdapter()
        host.disableOpticalSurfaceRendererForAdapter()
        host.invalidate()
    }

    private fun canDrawNavigation(): Boolean {
        if (!host.hasWindowFocus() || host.windowVisibility != View.VISIBLE) return false
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
    override fun hostChromeAlpha(host: View, selectedIndex: Int): Float =
        if (canDrawNavigation()) 1f else 0f
    override fun allowsHostTouch(): Boolean = canDrawNavigation()

    override fun drawFixedNavigationSlot(
        canvas: Canvas, index: Int, selectedIndex: Int, dark: Boolean,
        centerX: Float, centerY: Float, boxSize: Float,
    ): Boolean {
        val source = host.adapterNavigationSource ?: return true
        val scale = boxSize / min(source.width / 5f, source.height.toFloat()).coerceAtLeast(1f)
        if (canDrawNavigation()) tabsRenderer.drawSlot(canvas, source,
            5, index, centerX, host.height / 2f, scale, host.config.iconOnly)
        return true
    }

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
        val safe = ArrayList<View>()
        // Never retain a RenderNode whose descendants include a SurfaceView
        // compositor hole or a TextureView external texture. Split such a
        // branch into safe siblings, retaining Android's native draw order.
        fun collect(view: View): Boolean {
            if (view === host || view === host.adapterNavigationSource ||
                view is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay ||
                view.visibility != View.VISIBLE || view.alpha <= 0.001f) return true
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

    // Home and Shorts no longer use an idle timer or scroll-to-hide policy.
    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean = canDrawNavigation()

    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean {
        if (isShortsActive()) return true
        if (selectedIndex != 0) return false
        val source = host.adapterNavigationSource ?: return false
        if (!source.isAttachedToWindow) return false
        // Keep Home's projected bar through native scrolling, but let the
        // normal watch page continue to hide it when a video is opened.
        val root = host.rootView
        val watchId = root.resources.getIdentifier(
            "next_gen_watch_layout_no_player_fragment_container", "id", "com.google.android.youtube",
        )
        return watchId == 0 || root.findViewById<View>(watchId)?.isShown != true
    }

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
        tabsRenderer.clear()
        opticalCache.clear()
        shortsLayout.restore()
        videoSampler.dispose()
        resetShortsState()
    }

    override fun dispose() {
        tabsRenderer.clear()
        opticalCache.clear()
        shortsLayout.restore()
        videoSampler.dispose()
        resetShortsState()
        badgeRects = emptyList()
        recyclerClassCache.clear()
        splitSceneBranches.clear()
    }

    private fun pivotBarSelectsShorts(): Boolean? {
        val source = host.adapterNavigationSource ?: return null
        if (!source.isShown) return null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += source to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is TextView && view.isSelected) {
                val label = view.contentDescription?.toString() ?: view.text?.toString().orEmpty()
                return label.contains("Shorts", ignoreCase = true)
            }
            if (view is ViewGroup && depth < 4) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return null
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
        opticalCache.clear()
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
