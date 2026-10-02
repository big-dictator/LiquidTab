package io.github.offlineglass.hook.adapters.mi_calendar

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.android.calendar.homepage.BottomNavigatorView", listOf("onAttachedToWindow", "setVisibility", "g")),
        )
