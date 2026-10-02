package io.github.offlineglass.hook.adapters.mi_market

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.xiaomi.market.widget.BottomTabLayout", listOf("addFragmentTab", "selectTab", "show")),
            TargetHookSignal("com.xiaomi.market.business_ui.main.MarketTabActivity", listOf("onCreate", "onResume", "onConfigurationChanged")),
        )
