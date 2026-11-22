package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup

/** All four page veils are temporarily paused for navigation-inset observation. */
internal class WeChatVisualOrchestrator {
    @Suppress("UNUSED_PARAMETER")
    fun update(host: View, source: ViewGroup?, selectedProvider: () -> Int, enabled: Boolean, density: Float, fold: WeChatFoldController, probe: WeChatContentTargetProbe, veil: WeChatVeilController, surfaceColor: () -> Int) {
        veil.clearContacts()
        veil.clearChatList()
        veil.clearNavigationBlend()
        probe.clear()
    }
}