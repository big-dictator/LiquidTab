package io.github.offlineglass.hook.adapters.meituan

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("meituan", "com.sankuai.meituan.takeoutnew", "美团外卖", AdapterSource.DEDICATED,
            activityHints = setOf("com.sankuai.waimai.business.page.homepage.MainActivity"),
            idHints = setOf("bottom_navigation", "tab"),
            classHints = setOf("com.sankuai.waimai.business.page.homepage.view.TitleIndicator", "MainActivity"),
            textHints = setOf("首页", "神券", "订单", "我的"), preferredSlots = 5..5,
            defaultAccentColor = 0xFFFFC300.toInt(), entryLabels = listOf("首页", "神券", "活动", "订单", "我的"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFFC300.toInt(), sceneVisibilityAnimation = true)
