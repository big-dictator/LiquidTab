package io.github.offlineglass.hook.adapters.jd

import android.graphics.RenderNode

/** JD's H5/native feed must use one compositor readback, never a second live scene. */
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
