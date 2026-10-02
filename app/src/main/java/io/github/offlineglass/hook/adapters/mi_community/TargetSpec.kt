package io.github.offlineglass.hook.adapters.mi_community

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_community", "com.xiaomi.vipaccount", "小米社区", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.xiaomi.mi.launch.LaunchActivity",
                "com.xiaomi.vipaccount.ui.home.page.HomeFrameActivity",
            ),
            idHints = setOf("tab_indicator", "nav_item_0"),
            classHints = setOf(
                "com.xiaomi.vipaccount.ui.widget.tab.BottomNavView",
                "com.xiaomi.vipaccount.ui.widget.tab.NavItemView",
                "LaunchActivity",
            ),
            textHints = setOf("论坛", "官方", "活动", "消息", "我的"),
            preferredSlots = 5..5, entryLabels = listOf("论坛", "官方", "活动", "消息", "我的"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFF6900.toInt(), sceneVisibilityAnimation = true)
