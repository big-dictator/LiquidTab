package io.github.offlineglass.hook.adapters.tieba

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.globalGlassBottomGapPx
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.widget.FrameLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import android.os.SystemClock
import kotlin.math.abs

/** Owns Tieba's per-frame native chrome and floating home controls. */
internal class TiebaNavigationState(private val glassHost: GlassHostLayout) : AppNavigationState {
    private val baseTranslations = java.util.WeakHashMap<View, Float>()
    private val clearedBottomBlocks = java.util.WeakHashMap<View, Boolean>()
    private var recommendControl: View? = null
    private var liveSearchControl: View? = null
    private var lastProbeAt = 0L
    private var lastBlockScanAt = 0L
    private var publishArtwork: FrameLayout? = null
    private var publishAlpha = 1f
    private val publishPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastAnimatedCapture = 0L
    private val bottomArtwork = TiebaBottomArtwork()
    private val bottomGradient = TiebaEdgeGradient(top = false)
    private val feedBottomInsets = TiebaFeedBottomInsets()
    private var isEnabled = true

    override fun onHostFrame(host: View) {
        isEnabled = glassHost.config.enabled
        feedBottomInsets.update(glassHost, isEnabled)
        if (isEnabled) bottomArtwork.prepareOptical(glassHost.navigationSource)
        bottomGradient.updateBottom(glassHost, isEnabled)
    }

    override fun navigationOpticalMixEnabled(): Boolean = isEnabled && bottomArtwork.opticalReady

    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean): Boolean =
        navigationOpticalMixEnabled()

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> =
        (0 until hostIndex).map(content::getChildAt)
            .filterNot { it is AdapterOwnedOverlay || it is GlassHostLayout }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (frame.hideSelectedForOptics) {
            if (bottomArtwork.opticalReady) {
                bottomArtwork.drawOptical(canvas, frame, selected = true)
                if (frame.barLayout == null) drawPublish(canvas, frame)
            }
        } else {
            glassHost.drawOriginalNavigationForAdapter(canvas, frame.extraScale)
        }
    }

    override fun captureNavigation(source: ViewGroup, target: android.graphics.Bitmap): Boolean =
        glassHost.config.bottomBarIconOnly && bottomArtwork.capture(source, target)

    override fun sourceContentOffsetY(
        index: Int,
        selectedIndex: Int,
        hostHeight: Int,
        transformDy: Float,
        transformScale: Float,
    ): Float? {
        if (!glassHost.config.bottomBarIconOnly) return null
        return bottomArtwork.iconCenter(index)?.let { hostHeight / 2f - transformDy - it * transformScale }
    }

    override fun followingNativeNavigationAnimation(now: Long): Boolean =
        TiebaNativeTabAnimation.isFollowing(glassHost.navigationSource, now)

    override fun skipNavigationSnapshotCapture(
        source: ViewGroup,
        snapshot: android.graphics.Bitmap?,
        touching: Boolean,
        visualAnimating: Boolean,
        slotCount: Int,
        now: Long,
    ): Boolean? {
        if (!TiebaNativeTabAnimation.isFollowing(source, now)) return null
        if (snapshot != null && now - lastAnimatedCapture < 32L) return true
        lastAnimatedCapture = now
        glassHost.postInvalidateDelayed(32L)
        return false
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        glassHost.suppressNativeTiebaBottomBar(source)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        val glassHost = host as? GlassHostLayout ?: return
        val root = glassHost.rootView as? ViewGroup ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastProbeAt >= 240L) {
            lastProbeAt = now
            updatePublishArtwork(source)
            recommendControl = replaceControl(recommendControl,
                findLabel(root) { text -> RECOMMEND_LABELS.any(text::contains) }
                    ?.let { findBottomControl(it, root, glassHost.resources.displayMetrics.density) })
            liveSearchControl = replaceControl(liveSearchControl,
                findLabel(root) { text -> LIVE_SEARCH_LABELS.any(text::contains) }
                    ?.let { findBottomControl(it, root, glassHost.resources.displayMetrics.density) })
        }
        liftControl(glassHost, recommendControl); liftControl(glassHost, liveSearchControl)
        clearBottomBlock(glassHost, root, now)
    }

    override fun dispose() {
        bottomGradient.clear()
        feedBottomInsets.restore()
        bottomArtwork.clear()
        publishArtwork?.alpha = publishAlpha
        publishArtwork = null
        baseTranslations.forEach { (view, base) -> if (view.isAttachedToWindow) view.translationY = base }
        baseTranslations.clear(); recommendControl = null; liveSearchControl = null
        clearedBottomBlocks.clear(); lastProbeAt = 0L; lastBlockScanAt = 0L
    }

    private fun updatePublishArtwork(source: ViewGroup?) {
        if (publishArtwork?.isAttachedToWindow == true) return
        publishArtwork = null
        if (source == null || source.childCount != 5 || !source.getChildAt(2).hasOnClickListeners()) return
        var parent = source.parent as? ViewGroup
        repeat(5) {
            val container = parent ?: return
            val matches = (0 until container.childCount).map(container::getChildAt)
                .filterIsInstance<FrameLayout>()
                .filter { candidate ->
                    val childTypes = (0 until candidate.childCount)
                        .map { candidate.getChildAt(it).javaClass.name }
                    PUBLISH_ARTWORK_TYPES.all(childTypes::contains)
                }
            if (matches.size > 1) return
            matches.singleOrNull()?.let {
                publishAlpha = it.alpha
                publishArtwork = it
                it.alpha = 0f
                android.util.Log.i("LiquidTabTieba", "Bound native bottom publish artwork")
                return
            }
            parent = container.parent as? ViewGroup
        }
    }

    override fun drawForeground(
        canvas: Canvas,
        frame: AdapterNavigationFrame,
        selectionPath: Path,
        drawSource: () -> Unit,
    ): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty || !bottomArtwork.opticalReady) {
            drawSource()
            drawPublish(canvas, frame)
            return true
        }
        if (mix < 1f) {
            val baseline = canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                ((1f - mix) * 255f).toInt())
            drawSource()
            if (frame.barLayout == null) drawPublish(canvas, frame)
            canvas.restoreToCount(baseline)
        }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        val fade = if (mix < 1f) canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
            (mix * 255f).toInt()) else -1
        bottomArtwork.drawOptical(canvas, frame, selected = false)
        if (frame.barLayout == null) drawPublish(canvas, frame)
        if (fade >= 0) canvas.restoreToCount(fade)
        canvas.restoreToCount(outside)
        if (frame.barLayout != null) drawPublish(canvas, frame)
        return true
    }

    private fun drawPublish(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (publishArtwork?.isAttachedToWindow != true) return
        val layout = frame.barLayout
        if (layout == null && 2 !in frame.enabledIndices) return
        val x = layout?.let { it.actionLeft + it.actionSize / 2f }
            ?: frame.width * (frame.enabledIndices.indexOf(2) + 0.5f) / frame.enabledIndices.size
        val y = frame.height / 2f
        if (layout == null) {
            publishPaint.style = Paint.Style.FILL
            publishPaint.color = TIEBA_PUBLISH_BLUE
            val halfWidth = 16f * frame.density * TiebaAdapter.TAB_ICON_SCALE
            val halfHeight = 12f * frame.density * TiebaAdapter.TAB_ICON_SCALE
            canvas.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight,
                8f * frame.density, 8f * frame.density, publishPaint)
        }
        publishPaint.style = Paint.Style.STROKE
        publishPaint.strokeCap = Paint.Cap.ROUND
        publishPaint.strokeWidth = 2f * frame.density * TiebaAdapter.TAB_ICON_SCALE
        publishPaint.color = Color.WHITE
        val arm = 6f * frame.density * TiebaAdapter.TAB_ICON_SCALE
        canvas.drawLine(x - arm, y, x + arm, y, publishPaint)
        canvas.drawLine(x, y - arm, x, y + arm, publishPaint)
    }

    private fun findLabel(root: ViewGroup, matches: (String) -> Boolean): View? {
        val queue = ArrayDeque<Pair<View, Int>>(); queue += root to 0
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 4096) {
            val (view, depth) = queue.removeFirst()
            if (view.visibility != View.VISIBLE || view.alpha <= .01f) continue
            val text = (view as? android.widget.TextView)?.text?.toString().orEmpty()
            val description = view.contentDescription?.toString().orEmpty()
            if ((text.isNotEmpty() && matches(text)) || (description.isNotEmpty() && matches(description))) return view
            if (view is ViewGroup && depth < 32) for (index in 0 until view.childCount) queue += view.getChildAt(index) to depth + 1
        }
        return null
    }

    private fun findBottomControl(label: View, root: ViewGroup, density: Float): View? {
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val minimumBottom = rootLocation[1] + root.height - 360f * density
        val maximumHeight = (120f * density).toInt()
        var current: View? = label; var best: View? = null
        repeat(8) {
            val candidate = current ?: return@repeat
            if (candidate === root) return@repeat
            val location = IntArray(2).also(candidate::getLocationInWindow)
            if (candidate.visibility == View.VISIBLE && candidate.alpha > .01f &&
                location[1] + candidate.height >= minimumBottom &&
                candidate.width in 1..(root.width * .96f).toInt() && candidate.height in 1..maximumHeight) {
                if (candidate.isClickable || candidate.hasOnClickListeners()) return candidate
                if (candidate === label || candidate.background != null) best = candidate
            }
            current = candidate.parent as? View
        }
        return best
    }

    private fun replaceControl(previous: View?, current: View?): View? {
        if (previous !== current) baseTranslations.remove(previous)?.let { previous?.translationY = it }
        return current
    }

    private fun liftControl(host: GlassHostLayout, target: View?) {
        if (target == null || !target.isAttachedToWindow || target.visibility != View.VISIBLE) return
        val hostRect = Rect(); if (!host.getGlobalVisibleRect(hostRect)) return
        val base = baseTranslations.getOrPut(target) { target.translationY }
        val location = IntArray(2).also(target::getLocationInWindow)
        val naturalBottom = location[1] + target.height - (target.translationY - base)
        val desired = base + minOf(0f, hostRect.top - globalGlassBottomGapPx(host) - naturalBottom)
        if (abs(target.translationY - desired) > .5f) target.translationY = desired
    }

    private fun clearBottomBlock(host: GlassHostLayout, root: ViewGroup, now: Long) {
        if (now - lastBlockScanAt < 600L || root.width <= 0 || root.height <= 0) return
        lastBlockScanAt = now
        val hostRect = Rect(); if (!host.getGlobalVisibleRect(hostRect)) return
        val density = host.resources.displayMetrics.density
        val stack = ArrayDeque<View>(); stack += root; var visited = 0
        while (stack.isNotEmpty() && visited++ < 3072) {
            val view = stack.removeLast()
            if (view === host || view.visibility != View.VISIBLE) continue
            if (view is ViewGroup) {
                val rect = Rect()
                if (view.getGlobalVisibleRect(rect) && rect.width() >= root.width - (144f * density).toInt() &&
                    rect.top >= hostRect.bottom - (48f * density).toInt() &&
                    rect.top < hostRect.bottom + (640f * density).toInt() &&
                    rect.bottom >= root.height - (56f * density).toInt() && rect.height() >= (20f * density).toInt() &&
                    clearedBottomBlocks[view] != true && (view.background != null || view.foreground != null)) {
                    glassHost.clearTiebaSurface(view)
                    clearedBottomBlocks[view] = true; host.postInvalidateOnAnimation()
                }
                for (index in 0 until view.childCount) stack += view.getChildAt(index)
            }
        }
    }

    private companion object {
        val PUBLISH_ARTWORK_TYPES = setOf(
            "com.baidu.tbadk.widget.lottie.TBLottieAnimationView",
            "com.baidu.tbadk.widget.TbImageView",
        )
        val RECOMMEND_LABELS = arrayOf("单列浏览", "修改浏览设置", "浏览设置")
        val LIVE_SEARCH_LABELS = arrayOf("搜索你想看的直播", "搜你想看")
    }
}
