package io.github.offlineglass.hook.adapters.mi_community

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.xiaomi.vipaccount.ui.widget.tab.BottomNavView", listOf("onAttachedToWindow", "setSelectedItemPos", "updateSelectedItemPos", "bindData")),
            TargetHookSignal("com.xiaomi.vipaccount.ui.home.page.HomeFrameActivity", listOf("onCreate", "onResume", "onConfigurationChanged")),
        )
