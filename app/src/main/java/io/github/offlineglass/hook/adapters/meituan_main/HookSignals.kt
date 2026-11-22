package io.github.offlineglass.hook.adapters.meituan_main

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.meituan.android.pt.homepage.tab.TabBlockV2", listOf("onAttachedToWindow", "onSizeChanged")),
            TargetHookSignal("com.meituan.android.pt.homepage.activity.MainActivity", listOf("onResume", "onWindowFocusChanged")),
        )
