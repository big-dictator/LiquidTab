package io.github.offlineglass.hook.adapters.mihome

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew", listOf("onAttachedToWindow", "setViewPager", "setCurrentItem")),
        )
