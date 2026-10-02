package io.github.offlineglass.hook.adapters.bilibili_in

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal(
                "tv.danmaku.bili.home.components.bottomtab.WindowAwareComposeView",
                listOf("onAttachedToWindow", "onLayout", "onSizeChanged"),
            ),
            TargetHookSignal(
                "tv.danmaku.bili.MainActivityV2",
                listOf("onResume", "onWindowFocusChanged"),
            ),
        )
