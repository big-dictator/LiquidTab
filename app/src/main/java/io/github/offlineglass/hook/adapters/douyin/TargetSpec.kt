package io.github.offlineglass.hook.adapters.douyin

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("douyin", "com.ss.android.ugc.aweme", "抖音", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.ss.android.ugc.aweme.splash.SplashActivity",
                "com.ss.android.ugc.aweme.main.MainActivity",
            ),
            // 40.3.0: cta > a9+ > ctu > root_view is the authoritative
            // five-cell row. The centre cell is the native publish action.
            idHints = setOf("root_view", "ctu", "cta"),
            classHints = setOf("SplashActivity", "MainActivity", "LinearLayout"),
            textHints = setOf("首页", "朋友", "消息", "我"),
            preferredSlots = 5..5,
            supportsPostButton = true,
            entryLabels = listOf("首页", "朋友", "发布", "消息", "我"),
            defaultAccentColor = 0xFFFE2C55.toInt(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFE2C55.toInt())
