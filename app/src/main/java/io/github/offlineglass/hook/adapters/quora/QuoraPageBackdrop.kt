package io.github.offlineglass.hook.adapters.quora

import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.WebView
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference

/**
 * Exposes one WebView hardware layer to the public glass hosts. HWUI paints
 * that layer during the window frame and the hosts reuse its texture.
 */
internal class QuoraPageBackdrop {
    private var source: WebView? = null
    private var sourceList = emptyList<View>()
    private var rejectedSource: WeakReference<WebView>? = null
    private var changedLayerType = false
    private var ready = false
    private var observer: ViewTreeObserver? = null
    private var commit: Runnable? = null
    private var bottom: GlassHostLayout? = null
    private var buttons = emptyList<GlassHostLayout>()

    fun update(web: WebView?, navigation: GlassHostLayout, topControls: List<GlassHostLayout>) {
        bottom = navigation
        buttons = topControls
        val config = navigation.config
        val candidate = web?.takeIf {
            config.enabled && config.backdropCapture && !config.solidBarEnabled &&
                navigation.isShown && navigation.hasWindowFocus() && navigation.windowVisibility == View.VISIBLE &&
                it.isAttachedToWindow && it.isShown && it.isHardwareAccelerated &&
                it.width > 0 && it.height > 0 && it.layerType != View.LAYER_TYPE_SOFTWARE
        }
        if (source === candidate && (candidate == null || candidate.layerType == View.LAYER_TYPE_HARDWARE)) return
        releaseSource()
        if (candidate == null || rejectedSource?.get() === candidate) return
        rejectedSource = null

        val tree = candidate.viewTreeObserver
        if (!tree.isAlive) return
        source = candidate
        changedLayerType = candidate.layerType == View.LAYER_TYPE_NONE
        if (changedLayerType) candidate.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val callback = Runnable {
            navigation.post {
                if (source !== candidate || observer !== tree) return@post
                observer = null
                commit = null
                if (candidate.layerType != View.LAYER_TYPE_HARDWARE ||
                    !candidate.isAttachedToWindow || navigation.retainedBackdropNodeForAdapter(candidate) == null
                ) {
                    rejectedSource = WeakReference(candidate)
                    Log.w("LiquidTabQuoraGlass", "Could not obtain a committed WebView hardware layer")
                    releaseSource()
                    return@post
                }
                sourceList = listOf(candidate)
                ready = true
                Log.i("LiquidTabQuoraGlass", "WebView hardware layer ready for shared glass")
                invalidateHosts()
            }
        }
        observer = tree
        commit = callback
        tree.registerFrameCommitCallback(callback)
        candidate.invalidate()
    }

    fun scene(): View? = source?.takeIf {
        ready && it.isAttachedToWindow && it.isShown && it.layerType == View.LAYER_TYPE_HARDWARE
    }

    fun sceneViews(): List<View> = if (scene() != null) sourceList else emptyList()

    fun dispose() {
        releaseSource()
        rejectedSource = null
        bottom = null
        buttons = emptyList()
    }

    private fun invalidateHosts() {
        fun invalidate(host: GlassHostLayout) {
            host.invalidateOpticalSceneForAdapter()
            host.postInvalidateOnAnimation()
        }
        bottom?.let(::invalidate)
        buttons.forEach(::invalidate)
    }

    private fun releaseSource() {
        val old = source ?: return
        ready = false
        source = null
        sourceList = emptyList()
        val callback = commit
        if (callback != null) observer?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(callback)
        observer = null
        commit = null
        // Drop every retained reference before the node can draw its functor
        // directly again. This also prevents old pages bleeding across tabs.
        invalidateHosts()
        if (changedLayerType && old.layerType == View.LAYER_TYPE_HARDWARE)
            old.setLayerType(View.LAYER_TYPE_NONE, null)
        changedLayerType = false
    }
}
