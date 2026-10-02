package io.github.offlineglass.hook.adapters.tieba

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.globalGlassBottomGapPx
import android.graphics.Rect
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

    override fun suppressNativeChrome(source: ViewGroup?) {
        glassHost.suppressNativeTiebaBottomBar(source)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        val glassHost = host as? GlassHostLayout ?: return
        val root = glassHost.rootView as? ViewGroup ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastProbeAt >= 240L) {
            lastProbeAt = now
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
        baseTranslations.forEach { (view, base) -> if (view.isAttachedToWindow) view.translationY = base }
        baseTranslations.clear(); recommendControl = null; liveSearchControl = null
        clearedBottomBlocks.clear(); lastProbeAt = 0L; lastBlockScanAt = 0L
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
        val RECOMMEND_LABELS = arrayOf("单列浏览", "修改浏览设置", "浏览设置")
        val LIVE_SEARCH_LABELS = arrayOf("搜索你想看的直播", "搜你想看")
    }
}
