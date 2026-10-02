package io.github.offlineglass.hook.adapters.meituan_main

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("meituan_main", "com.sankuai.meituan", "美团", AdapterSource.DEDICATED,
            activityHints = setOf("com.meituan.android.pt.homepage.activity.MainActivity"),
            idHints = setOf("bottom_navigation", "tab"), classHints = setOf("com.meituan.android.pt.homepage.tab.TabBlockV2", "MainActivity"),
            textHints = setOf("推荐", "视频", "AI", "购物车", "我的"), preferredSlots = 5..5,
            defaultAccentColor = -12032, entryLabels = listOf("推荐", "视频", "AI", "购物车", "我的"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFFC300.toInt(), sceneVisibilityAnimation = true)
