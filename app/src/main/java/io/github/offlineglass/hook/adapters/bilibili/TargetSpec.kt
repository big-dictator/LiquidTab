package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("bilibili", "tv.danmaku.bili", "哔哩哔哩", AdapterSource.DEDICATED,
            activityHints = setOf("tv.danmaku.bili.MainActivityV2"),
            idHints = setOf("bottom_navigation", "tab_host"),
            classHints = setOf("com.bilibili.lib.homepage.widget.TabHost", "HomeTabPublishView", "MainActivityV2"),
            textHints = setOf("首页", "关注", "会员购", "我的"),
            entryLabels = listOf("首页", "动态", "发布", "会员购", "我的"),
            preferredSlots = 5..5, supportsPostButton = true, defaultAccentColor = -298343)
