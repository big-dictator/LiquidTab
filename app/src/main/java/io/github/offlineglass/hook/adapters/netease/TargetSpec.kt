package io.github.offlineglass.hook.adapters.netease

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("netease", "com.netease.cloudmusic", "网易云音乐", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.netease.cloudmusic.activity.MainActivity",
                "com.netease.cloudmusic.music.biz.recentplay.ui.activity.MyRecentPlayActivity",
                "com.netease.cloudmusic.music.biz.rn.activity.MainProcessRNActivity",
            ),
            idHints = setOf("mainNavigationTabLayout"),
            classHints = setOf("com.netease.cloudmusic.theme.ui.NavigationTabLayout", "MainActivity"),
            textHints = setOf("首页", "搜索", "我的"), preferredSlots = 4..4,
            hideOptions = listOf(HideOption("隐藏搜索", 1), HideOption("隐藏笔记", 2)), entryLabels = listOf("首页", "搜索", "笔记", "我的"), glassAccentColor = 0xFFC20C0C.toInt(), sceneVisibilityAnimation = true)
