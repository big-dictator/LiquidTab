package io.github.offlineglass.hook.adapters.xjtu

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec("xjtu", "com.supwisdom.xjtu", "移动交通大学", AdapterSource.DEDICATED,
    classHints = setOf("supwisdom.rv"),
    textHints = setOf("首页", "服务", "事务", "日程", "我的"),
    preferredSlots = 5..5,
    defaultAccentColor = 0xFFED663F.toInt(),
    glassAccentColor = 0xFFED663F.toInt(),
    drawsNavigationInOptics = true,
    sceneVisibilityAnimation = true,
    entryLabels = listOf("首页", "服务", "事务", "日程", "我的"),
    uiProcessSuffixes = setOf("unimp0", "unimp1", "unimp2", "unimp3", "unimp4"),
)
