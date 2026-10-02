package io.github.offlineglass.hook.adapters.xhs

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("xhs", "com.xingin.xhs", "小红书", AdapterSource.DEDICATED,
            activityHints = setOf("com.xingin.xhs.index.v2.IndexActivityV2"),
            idHints = setOf("bottom_navigation", "tab_bar"),
            classHints = setOf("com.xingin.xhs.homepage.tabbar.TabBarView", "com.xingin.commercial.shop.malltabbar.TabBarView", "IndexActivityV2"),
            textHints = setOf("首页", "市集", "消息", "我的"),
            preferredSlots = 5..5, supportsPostButton = true, defaultAccentColor = -56254, entryLabels = listOf("首页", "市集", "发布", "消息", "我"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFF2442.toInt(), sceneVisibilityAnimation = true)
