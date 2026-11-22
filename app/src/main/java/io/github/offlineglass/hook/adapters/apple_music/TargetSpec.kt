package io.github.offlineglass.hook.adapters.apple_music

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec(
    key = "apple_music",
    packageName = "com.apple.android.music",
    displayName = "Apple Music",
    source = AdapterSource.DEDICATED,
    activityHints = setOf("com.apple.android.music.common.MainActivity"),
    preferredSlots = 2..5,
    defaultAccentColor = 0xFFFA2D48.toInt(),
    drawsNavigationInOptics = true,
)
