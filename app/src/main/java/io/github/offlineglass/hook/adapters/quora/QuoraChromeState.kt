package io.github.offlineglass.hook.adapters.quora

import io.github.offlineglass.hook.adapters.AppNavigationState
import android.view.View
import android.view.ViewGroup

/** Keep Quora's WebView under its native compositor's sole ownership. */
internal interface QuoraChromeState : AppNavigationState {
    val pageBackdrop: QuoraPageBackdrop

    override fun requiresSampledBackdrop(): Boolean = pageBackdrop.scene() != null
    override fun resolveBackdropScene(host: View): View? = pageBackdrop.scene()
    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> = pageBackdrop.sceneViews()
    override val allowsSoftwareBackdropCapture: Boolean get() = false
}
