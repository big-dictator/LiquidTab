package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout

/** Uses live GPU nodes only for verified native pages. */
internal class JdNativeSceneSelector {
    private var parent: ViewGroup? = null
    private var observer: ViewTreeObserver? = null
    private var dirty = true
    private var tab = -1
    private var nextProbe = 0L
    private var scene: View? = null
    private var navigation: ViewGroup? = null
    private val pendingViews = ArrayDeque<View>()
    private val rendererTypes = java.util.WeakHashMap<Class<*>, Boolean>()
    private var probing = false
    private fun isWebRenderer(view: View): Boolean {
        rendererTypes[view.javaClass]?.let { return it }
        val result = resolveWebRenderer(view)
        if (rendererTypes.size < 128) rendererTypes[view.javaClass] = result
        return result
    }
    private fun resolveWebRenderer(view: View): Boolean {
        if (view is android.webkit.WebView) return true
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.jingdong.hybrid.ui.JDWebView" ||
                type.name == "com.jd.libs.xwin.widget.XWebView" ||
                type.name == "com.jingdong.web.sdk.webkit.WebView") return true
            type = type.superclass
        }
        return false
    }
    private val listener = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }

    fun onFocusChanged(focused: Boolean) {
        if (focused) { dirty = true; nextProbe = 0L }
    }

    fun pick(host: GlassHostLayout, index: Int, unsafeChannel: Boolean): View? {
        if (unsafeChannel) {
            if (scene != null && android.os.Trace.isEnabled()) android.os.Trace.setCounter("LiquidTab.JD.sceneLive", 0)
            // Returning from H5 must revalidate the real visible page even
            // when its container identity and bottom-tab index did not change.
            scene = null; dirty = true; nextProbe = 0L
            return null
        }
        val container = host.parent as? ViewGroup ?: return null
        if (parent !== container) {
            detach()
            parent = container
            observer = container.viewTreeObserver.also { it.addOnGlobalLayoutListener(listener) }
        }
        val now = SystemClock.uptimeMillis()
        val source = host.navigationSource
        if (navigation !== source) { navigation = source; dirty = true; nextProbe = 0L; scene = null }
        if (tab != index) { tab = index; dirty = true; nextProbe = 0L; scene = null }
        if (probing) return scene
        if (!dirty || now < nextProbe) return scene?.takeIf { it.isAttachedToWindow && it.isShown }
        dirty = false
        nextProbe = now + 350L
        val row = JdNavigationBinding.resolve(source)?.view ?: run { scene = null; return null }
        val frame = JdNavigationBinding.mainFrame(row) ?: run { scene = null; return null }
        val root = JdClassicLayout.content(frame) ?: JdNativeContentBinding.scene(row)
            ?: run { scene = null; return null }
        val stack = pendingViews
        stack += root
        var visited = 0
        probing = true
        android.os.Trace.beginSection("LiquidTab.JD.sceneEligibility")
        try {
        while (stack.isNotEmpty() && visited++ < 2000) {
            val view = stack.removeLast()
            if (view.visibility != View.VISIBLE || view.alpha <= .001f) continue
            // A retained parent RenderNode can start drawing an offscreen
            // functor mid-scroll, before the next layout/eligibility pass.
            // Reject independent renderers even when currently clipped.
            val unsafe = view is android.view.SurfaceView || view is android.view.TextureView || isWebRenderer(view)
            if (unsafe) {
                scene = null
                return null
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                if (stack.size >= 2000) { scene = null; return null }
                stack += view.getChildAt(i)
            }
        }
        scene = root.takeIf { stack.isEmpty() }
        // Keep Android's existing child RenderNodes. A forced full-page
        // hardware layer is invalidated by every destination/scroll update,
        // rasterizing a screen-sized texture before sampling a narrow lens.
        // Clip the live nodes directly instead; optics and resolution remain.
        return scene
        } finally {
            stack.clear()
            probing = false
            if (android.os.Trace.isEnabled()) android.os.Trace.setCounter("LiquidTab.JD.sceneLive", if (scene == null) 0 else 1)
            android.os.Trace.endSection()
        }
    }

    fun detach() {
        if (observer?.isAlive == true) observer?.removeOnGlobalLayoutListener(listener)
        parent = null; observer = null; scene = null; navigation = null; tab = -1; nextProbe = 0L; dirty = true
        pendingViews.clear()
        rendererTypes.clear()
    }
}
