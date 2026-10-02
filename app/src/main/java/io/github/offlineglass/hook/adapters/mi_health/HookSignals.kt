package io.github.offlineglass.hook.adapters.mi_health

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.xiaomi.fitness.main.MainActivity", listOf("bindView", "onResume", "onConfigurationChanged")),
        )
