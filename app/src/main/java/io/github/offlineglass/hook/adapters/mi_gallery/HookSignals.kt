package io.github.offlineglass.hook.adapters.mi_gallery

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = commonMiuixSignals() + listOf(
            TargetHookSignal("com.miui.gallery.activity.HomePageActivity", listOf("onCreate")),
        )
