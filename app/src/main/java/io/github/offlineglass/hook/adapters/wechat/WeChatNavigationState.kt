package io.github.offlineglass.hook.adapters.wechat

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AppNavigationState

/** Mutable WeChat resources belong to one host, not the process-wide adapter. */
internal class WeChatNavigationState : AppNavigationState {
    private val tabsRenderer = WeChatTabsRenderer()
    private val frostController = WeChatNativeFrostController()
    private val miniProgramDetector = WeChatMiniProgramDetector()
    private val foldController = WeChatFoldController()
    private val contentTargetProbe = WeChatContentTargetProbe()
    private val veilController = WeChatVeilController()
    private val visualOrchestrator = WeChatVisualOrchestrator()
    private val recentDrawerDetector = WeChatRecentDrawerDetector()

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float,
        barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        foldController.adjust(host, source, selected(), density)
        recentDrawerDetector.update(host, source, density)
        visualOrchestrator.update(
            host, source, selected, enabled, density,
            foldController, contentTargetProbe, veilController, surfaceColor,
        )
    }

    override fun onHostFocusLost() {
        foldController.restoreAllWeChatFoldBarTranslations()
        foldController.weChatFoldBarTarget = null
        foldController.hideWeChatFoldTouchProxy()
    }

    override fun onHostDetached() {
        foldController.dispose()
        contentTargetProbe.clear()
        veilController.clearContacts()
        veilController.clearChatList()
        veilController.clearNavigationBlend()
    }

    override fun transientPageAllowsNavigation(activity: android.app.Activity?): Boolean =
        !miniProgramDetector.isVisible(activity)

    override fun suppressHostNativeChrome(source: ViewGroup?, root: View, selected: Int) {
        frostController.suppress(source, root, selected)
    }

    override fun drawNativeNavigation(canvas: Canvas, source: ViewGroup, density: Float): Boolean {
        tabsRenderer.draw(canvas, source, density)
        return true
    }
}
