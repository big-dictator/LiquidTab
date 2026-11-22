package io.github.offlineglass.hook.adapters.bilibili

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState

/** A home panel samples the native page below the reconstructed chrome. */
internal class BilibiliHomeGlassScene(private val page: View) : AppNavigationState {
    private val sources = listOf(page)
    private val inverseHost = Matrix()
    private var host: GlassHostLayout? = null
    private var liveOnPreviousFrame = false
    private var outerHasLiveInput = false

    override val allowsSoftwareBackdropCapture = false

    override fun resolveBackdropScene(host: View): View = page

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> =
        if (page.parent === content && page.isShown) sources else emptyList()

    override fun backdropSceneBaseColor(scene: View, fallback: Int): Int {
        var view: View? = scene
        while (view != null) {
            val color = (view.background as? ColorDrawable)?.color
            if (color != null && Color.alpha(color) == 255) return color
            view = view.parent as? View
        }
        return fallback
    }

    fun transform(parent: View, host: View, out: Matrix): Boolean {
        inverseHost.reset()
        host.transformMatrixToGlobal(inverseHost)
        if (!inverseHost.invert(inverseHost)) return false
        out.reset()
        parent.transformMatrixToGlobal(out)
        out.setConcat(inverseHost, out)
        return true
    }

    override fun onHostFrame(host: View) {
        val glass = host as? GlassHostLayout ?: return
        this.host = glass
        val live = glass.liveBackdropActiveForAdapter
        if (live != liveOnPreviousFrame) {
            liveOnPreviousFrame = live
            if (glass.isShown) glass.invalidate()
        }
    }

    override fun outerBackdropNeedsRecord(
        backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean,
    ): Boolean? {
        if (host?.liveBackdropActiveForAdapter != true) outerHasLiveInput = false
        // A first frame without a live scene must not be cached as live input.
        return if (!outerHasLiveInput) true else null
    }

    override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
        outerHasLiveInput = host?.liveBackdropActiveForAdapter == true
    }

    override fun onHostDetached() {
        host = null
        liveOnPreviousFrame = false
        outerHasLiveInput = false
    }
}
