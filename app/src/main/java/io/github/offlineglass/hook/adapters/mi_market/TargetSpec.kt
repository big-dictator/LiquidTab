package io.github.offlineglass.hook.adapters.mi_market

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_market", "com.xiaomi.market", "小米应用商店", AdapterSource.DEDICATED,
            activityHints = setOf("com.xiaomi.market.business_ui.main.MarketTabActivity"),
            idHints = setOf("tab_container", "tab_container_layout"),
            classHints = setOf("com.xiaomi.market.widget.BottomTabLayout", "MarketTabActivity"),
            textHints = setOf("首页", "游戏", "应用", "榜单", "我的"), preferredSlots = 5..5,
            defaultAccentColor = 0xFFFF765B.toInt(), entryLabels = listOf("首页", "游戏", "应用", "榜单", "我的"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFF765B.toInt(), sceneVisibilityAnimation = true)
