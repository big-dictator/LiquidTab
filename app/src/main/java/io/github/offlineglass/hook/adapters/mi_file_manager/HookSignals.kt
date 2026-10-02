package io.github.offlineglass.hook.adapters.mi_file_manager

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = commonMiuixSignals() + listOf(
            TargetHookSignal("com.android.fileexplorer.fragment.PhoneMainFragment", listOf("onVisibilityChanged")),
        )
