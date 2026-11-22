package io.github.offlineglass.hook.adapters.spotify

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec(
    key = "spotify",
    packageName = "com.spotify.music",
    displayName = "Spotify",
    source = AdapterSource.DEDICATED,
    activityHints = setOf("com.spotify.music.MainActivity"),
    idHints = setOf("bottom_navigation", "navigation_bar"),
    classHints = setOf("NavigationBar", "BottomNavigation"),
    textHints = setOf("Home", "Search", "Your Library", "Premium", "Create", "主页", "搜索", "音乐库", "创建"),
    preferredSlots = 5..5,
    entryLabels = listOf("主页", "搜索", "音乐库", "Premium", "创建"),
    defaultAccentColor = 0xFF1ED760.toInt(),
    glassAccentColor = 0xFF1ED760.toInt(),
)
