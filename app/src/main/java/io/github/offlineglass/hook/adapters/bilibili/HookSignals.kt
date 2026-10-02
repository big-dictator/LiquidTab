package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.bilibili.lib.homepage.widget.TabHost", listOf("onAttachedToWindow", "setTabs", "setCurrentItem")),
        )
