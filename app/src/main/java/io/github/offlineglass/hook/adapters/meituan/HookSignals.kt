package io.github.offlineglass.hook.adapters.meituan

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.sankuai.waimai.business.page.homepage.view.TitleIndicator", listOf("onAttachedToWindow", "setActivity", "setCurrentTab", "k")),
        )
