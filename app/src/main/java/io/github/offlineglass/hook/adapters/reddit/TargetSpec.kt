package io.github.offlineglass.hook.adapters.reddit

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec(
    key = "reddit", packageName = "com.reddit.frontpage", displayName = "Reddit",
    source = AdapterSource.DEDICATED,
    activityHints = setOf("com.reddit.launch.main.MainActivity"),
    preferredSlots = 3..6, defaultIconOnly = false,
    defaultAccentColor = 0xFFFF4500.toInt(),
)
