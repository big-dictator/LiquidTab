package io.github.offlineglass.hook.adapters.meituan_main

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Uses live GPU nodes only for verified native pages; media keeps PixelCopy. */
internal class MeituanMainSceneSelector(private val binding: MeituanMainBinding) {
    private var parent: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var dirty = true
    private var tab = -1
    private var scene: View? = null
    var samplingRoot: View? = null
        private set
    private var structure = Long.MIN_VALUE
    private fun structureKey(container: ViewGroup): Long {
        var key = container.width.toLong() * 31 + container.height
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is AdapterOwnedOverlay || child is GlassHostLayout) continue
            key = key * 31 + System.identityHashCode(child)
            key = key * 31 + child.visibility
            key = key * 31 + child.width
            key = key * 31 + child.height
        }
        return key
    }
    private val listener = ViewTreeObserver.OnGlobalLayoutListener {
        parent?.let {
            val next = structureKey(it)
            if (next != structure || scene?.isAttachedToWindow == false) {
                structure = next; dirty = true
            }
        }
    }

    fun onFocusChanged(focused: Boolean) {
        if (focused) dirty = true
    }

    fun pick(host: GlassHostLayout, index: Int, unsafeChannel: Boolean): View? {
        if (unsafeChannel) { return null }
        val container = host.parent as? ViewGroup ?: return null
        if (parent !== container) {
            detach()
            parent = container
            observer = container.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
        }
        if (tab != index) { tab = index; dirty = true; scene = null; samplingRoot = null }
        if (!dirty) return scene?.takeIf { it.isAttachedToWindow && it.isShown }
        dirty = false
        val hostIndex = container.indexOfChild(host)
        if (hostIndex < 0) return null
        var candidate: View? = null
        var bestArea = 0L
        for (i in 0 until hostIndex) {
            val view = container.getChildAt(i)
            if (view is AdapterOwnedOverlay || view is GlassHostLayout ||
                view is android.view.SurfaceView || !view.isShown || view.alpha <= .001f) continue
            val area = view.width.toLong() * view.height
            if (view.width >= container.width * .8f && view.height >= container.height * .5f && area > bestArea) {
                candidate = view; bestArea = area
            }
        }
        val candidateRoot = candidate ?: run { scene = null; samplingRoot = null; return null }
        // Native navigation redraws are not page-content revisions. Bind the
        // verified content sibling so capturing tab art cannot drive sampling.
        val root = MeituanMainNativeTabs.content(host.adapterNavigationSource)?.takeIf { content ->
            var owner: View? = content
            while (owner != null && owner !== candidateRoot) owner = owner.parent as? View
            owner === candidateRoot && content.isShown && content.width > 0 && content.height > 0
        } ?: candidateRoot
        samplingRoot = root
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 2000) {
            val view = stack.removeLast()
            if (view.visibility != View.VISIBLE || view.alpha <= .001f) continue
            if (view is android.view.SurfaceView || view is android.view.TextureView || view is android.webkit.WebView) {
                scene = null
                return null
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        scene = root.takeIf { stack.isEmpty() }
        // Keep Android's existing child RenderNodes. A forced full-page
        // hardware layer is invalidated by every destination/scroll update,
        // rasterizing a screen-sized texture before sampling a narrow lens.
        // Clip the live nodes directly instead; optics and resolution remain.
        return scene
    }

    fun detach() {
        samplingRoot = null
        if (observer?.isAlive == true) observer?.removeOnGlobalLayoutListener(listener)
        parent = null; observer = null; scene = null; tab = -1; dirty = true; structure = Long.MIN_VALUE
    }
}


