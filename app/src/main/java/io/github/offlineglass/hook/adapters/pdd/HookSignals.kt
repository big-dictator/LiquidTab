package io.github.offlineglass.hook.adapters.pdd

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView", listOf("onAttachedToWindow", "onLayout", "onSizeChanged", "setTabs", "setOnTabChangeListener")),
            TargetHookSignal("com.xunmeng.pinduoduo.ui.activity.MainFrameActivity", listOf("updateRootView", "backToHome")),
        )
