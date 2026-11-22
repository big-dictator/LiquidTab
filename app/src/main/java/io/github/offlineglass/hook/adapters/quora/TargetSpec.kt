package io.github.offlineglass.hook.adapters.quora

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec(
    key = "quora", packageName = "com.quora.android", displayName = "Quora",
    source = AdapterSource.DEDICATED,
    activityHints = setOf("com.quora.android.components.activities.QuoraActivity"),
    idHints = setOf("tab_bar_tabs"), preferredSlots = 3..6,
    defaultIconOnly = true, defaultAccentColor = 0xFFB92B27.toInt(),
    entryLabels = listOf("首页", "关注", "回答", "空间", "通知"),
    sceneVisibilityAnimation = true,
)
