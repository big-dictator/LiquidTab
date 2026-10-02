package io.github.offlineglass.hook.adapters.qqmusic

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec("qqmusic", "com.tencent.qqmusic", "QQ音乐", AdapterSource.DEDICATED,
    idHints = setOf("g5b"),
    classHints = setOf("MainDeskNavigateContainer"),
    preferredSlots = 3..6,
    defaultAccentColor = 0xFF00C982.toInt(),
    entryLabels = listOf("首页", "视频", "刷歌", "星光", "我的"),
)
