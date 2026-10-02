package io.github.offlineglass.hook.adapters.wechat

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("wechat", "com.tencent.mm", "微信", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf("com.tencent.mm.ui.LauncherUI"), idHints = setOf("bottom_navigation", "tab", "launcher_ui"),
            classHints = setOf("LauncherUI"), textHints = setOf("微信", "通讯录", "发现", "我"), preferredSlots = 4..4, entryLabels = listOf("微信", "通讯录", "发现", "我"), drawsNavigationInOptics = true, glassAccentColor = 0xFF07C160.toInt(), sceneVisibilityAnimation = true)
