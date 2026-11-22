package io.github.offlineglass.hook.adapters.tiktok

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec("tiktok", "com.zhiliaoapp.musically", "TikTok", AdapterSource.DEDICATED,
    activityHints = setOf("com.ss.android.ugc.aweme.main.MainActivity", "com.ss.android.ugc.aweme.splash.SplashActivity"),
    idHints = emptySet(),
    preferredSlots = 5..5,
    supportsPostButton = true,
    entryLabels = listOf("首页", "朋友", "发布", "收件箱", "个人主页"),
    defaultAccentColor = 0xFFFE2C55.toInt(),
    drawsNavigationInOptics = true,
    glassAccentColor = 0xFFFE2C55.toInt(),
    sceneVisibilityAnimation = true,
    installUiHooks = true,
    hooksAllProcesses = true,
)
