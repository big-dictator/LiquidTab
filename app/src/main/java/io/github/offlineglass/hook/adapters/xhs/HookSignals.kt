package io.github.offlineglass.hook.adapters.xhs

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.xingin.xhs.homepage.tabbar.TabBarView", listOf("onAttachedToWindow", "onFinishInflate")),
            TargetHookSignal("com.xingin.commercial.shop.malltabbar.TabBarView", listOf("onAttachedToWindow", "onFinishInflate")),
        )
