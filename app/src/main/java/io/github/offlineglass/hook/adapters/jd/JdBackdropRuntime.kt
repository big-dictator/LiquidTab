package io.github.offlineglass.hook.adapters.jd

import android.graphics.RenderNode

/** JD's custom feeds use a composed strip, avoiding a second WebView draw. */
internal object JdBackdropRuntime {
    inline fun activateCompositor(
        liveNode: RenderNode,
        liveActive: Boolean,
        cache: JdOpticalCache,
        disableNativeBlur: () -> Unit,
        clearLiveHostState: () -> Unit,
        enableSurfaceRenderer: () -> Unit,
    ) {
        disableNativeBlur()
        if (liveActive || liveNode.hasDisplayList()) {
            liveNode.setRenderEffect(null)
            liveNode.discardDisplayList()
            cache.liveBackdropEpoch = -1
            cache.liveBackdropTab = -1
            clearLiveHostState()
        }
        enableSurfaceRenderer()
    }
}
