package io.github.offlineglass.hook.adapters.pdd

import android.os.SystemClock
import android.graphics.Matrix
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.RetainedNavigationBackdrop
import java.lang.ref.WeakReference

/** Lets the glass sample a committed hardware layer without replaying Meco's draw commands. */
internal class PddPromotionBackdrop {
    private var host: GlassHostLayout? = null
    private var content: View? = null
    private var source: View? = null
    private var rejectedSource: WeakReference<View>? = null
    private var ownsLayer = false
    private var ready = false
    private var observer: ViewTreeObserver? = null
    private var commit: Runnable? = null
    private var nextProbeAt = 0L
    private val toScreen = Matrix()
    private var reportedMissingSource = false

    fun update(host: GlassHostLayout, content: View?) {
        val config = host.config
        if (content == null || !config.enabled || !config.backdropCapture || config.solidBarEnabled ||
            !host.isShown || !host.hasWindowFocus() || host.windowVisibility != View.VISIBLE
        ) {
            release()
            return
        }
        if (this.content !== content || this.host !== host) {
            release()
            this.content = content
            this.host = host
            rejectedSource = null
            nextProbeAt = 0L
        }
        val current = source
        if (current?.let { it.isAttachedToWindow && it.isShown &&
                it.layerType == View.LAYER_TYPE_HARDWARE } == true
        ) return
        releaseSource()
        val now = SystemClock.uptimeMillis()
        if (now < nextProbeAt) return
        nextProbeAt = now + 250L
        val web = findPageWebView(content)
        if (web == null) {
            if (!reportedMissingSource && content.width > 0 && content.height > 0) {
                reportedMissingSource = true
                Log.w("LiquidTabPdd", "Promotion content has no unique full-size WebView")
            }
            return
        }
        reportedMissingSource = false
        if (rejectedSource?.get() === web) return
        val tree = web.viewTreeObserver
        if (!tree.isAlive) return
        source = web
        ownsLayer = web.layerType == View.LAYER_TYPE_NONE
        if (ownsLayer) web.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val callback = Runnable {
            host.post {
                if (source !== web || observer !== tree) return@post
                observer = null
                commit = null
                if (!web.isAttachedToWindow || web.layerType != View.LAYER_TYPE_HARDWARE ||
                    host.retainedBackdropNodeForAdapter(web) == null
                ) {
                    Log.w("LiquidTabPdd", "Promotion WebView committed without a usable hardware layer")
                    rejectedSource = WeakReference(web)
                    releaseSource()
                    return@post
                }
                ready = true
                host.restoreOpticalSceneForAdapter()
                Log.i("LiquidTabPdd", "Promotion hardware layer ready: ${web.width}x${web.height}, liquid=${host.config.liquidGlassEnabled}")
            }
        }
        observer = tree
        commit = callback
        tree.registerFrameCommitCallback(callback)
        web.invalidate()
    }

    fun scene(): View? = source?.takeIf {
        ready && it.isAttachedToWindow && it.isShown && it.layerType == View.LAYER_TYPE_HARDWARE
    }

    fun isSourceVisible(): Boolean = source?.let { it.isAttachedToWindow && it.isShown } == true

    fun retainedScene(): RetainedNavigationBackdrop? {
        val web = scene() ?: return null
        val parent = web.parent as? View ?: return null
        val node = host?.retainedBackdropNodeForAdapter(web) ?: return null
        // drawRenderNode already applies the WebView's own positioning and matrix.
        // Supply only the parent's transform and its scroll offset.
        toScreen.reset()
        parent.transformMatrixToGlobal(toScreen)
        toScreen.preTranslate(-parent.scrollX.toFloat(), -parent.scrollY.toFloat())
        return RetainedNavigationBackdrop(node, toScreen)
    }

    fun release(sceneReplaced: Boolean = false) {
        releaseSource(sceneReplaced)
        host = null
        content = null
    }

    private fun releaseSource(sceneReplaced: Boolean = false) {
        val old = source ?: return
        ready = false
        source = null
        commit?.let { callback -> observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback) }
        commit = null
        observer = null
        // Drop retained references before restoring direct WebView drawing.
        if (!sceneReplaced) {
            host?.invalidateOpticalSceneForAdapter()
            host?.postInvalidateOnAnimation()
        }
        if (ownsLayer && old.layerType == View.LAYER_TYPE_HARDWARE) old.setLayerType(View.LAYER_TYPE_NONE, null)
        ownsLayer = false
    }

    private fun findPageWebView(content: View): View? {
        if (content.width <= 0 || content.height <= 0) return null
        val queue = ArrayDeque<View>().apply { add(content) }
        var result: View? = null
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 48) return null
            val view = queue.removeFirst()
            if (!view.isShown || view.width < content.width * .9f || view.height < content.height * .7f) continue
            if (view is WebView || view.accessibilityClassName.toString() == "meco.webkit.WebView") {
                if (result != null) return null
                result = view
                continue
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
        }
        return result?.takeIf { it.isAttachedToWindow && it.isHardwareAccelerated &&
            it.layerType != View.LAYER_TYPE_SOFTWARE }
    }
}
