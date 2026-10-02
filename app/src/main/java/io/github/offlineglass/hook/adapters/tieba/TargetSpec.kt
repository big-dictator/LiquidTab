package io.github.offlineglass.hook.adapters.tieba

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("tieba", "com.baidu.tieba", "百度贴吧", AdapterSource.GENERIC_FALLBACK,
            // Baidu Tieba is heavily obfuscated (all resource IDs resolve to
            // "obfuscated", no custom view class names in the bar). The bottom
            // navigation is a plain horizontal LinearLayout of five clickable
            // tab cells (icon + label). Detection relies on geometry + text
            // matching; the five labels are 首页 / 进吧 / 小卖部 / 消息 / 我的.
            activityHints = setOf(
                "com.baidu.tieba.tblauncher.MainTabActivity",
                "com.baidu.tieba.app.MainActivity",
            ),
            idHints = setOf("bottom_navigation", "tab_bar", "navigation_bar"),
            classHints = setOf("MainTabActivity", "BottomTabLayout", "TabBar"),
            textHints = setOf("首页", "进吧", "小卖部", "消息", "我的"),
            preferredSlots = 5..5, supportsPostButton = false,
            defaultAccentColor = 0xFF2B7AFE.toInt(), entryLabels = listOf("首页", "进吧", "小卖部", "消息", "我的"), drawsNavigationInOptics = true, sceneVisibilityAnimation = true)
