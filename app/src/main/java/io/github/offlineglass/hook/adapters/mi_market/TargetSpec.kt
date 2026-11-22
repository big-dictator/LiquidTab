package io.github.offlineglass.hook.adapters.mi_market

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_market", "com.xiaomi.market", "小米应用商店", AdapterSource.DEDICATED,
            activityHints = setOf("com.xiaomi.market.business_ui.main.MarketTabActivity"),
            idHints = setOf("tab_container", "tab_container_layout"),
            classHints = setOf("com.xiaomi.market.widget.BottomTabLayout", "MarketTabActivity"),
            textHints = setOf("首页", "榜单", "我的"), preferredSlots = 3..3,
            defaultAccentColor = 0xFFFF6900.toInt(), entryLabels = listOf("首页", "我", "搜索"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFF6900.toInt(), sceneVisibilityAnimation = true)
