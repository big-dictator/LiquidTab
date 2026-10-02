package io.github.offlineglass.hook.adapters.cainiao

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("cainiao", "com.cainiao.wireless", "菜鸟", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf(
                "com.cainiao.wireless.homepage.view.activity.HomePageActivity",
            ),
            idHints = setOf("navigation_bar_layout", "navigation_tab_view", "ll_navigation_tab_layout"),
            classHints = setOf("HomePageActivity", "LinearLayout", "FrameLayout"),
            textHints = setOf("首页", "发现", "购物券", "消息", "我的"),
            preferredSlots = 5..5, supportsPostButton = false,
            entryLabels = listOf("首页", "发现", "购物券", "消息", "我的"),
            defaultAccentColor = 0xFFFF6A00.toInt(), drawsNavigationInOptics = true)
