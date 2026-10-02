package io.github.offlineglass.hook.adapters.youtube

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("youtube", "com.google.android.youtube", "YouTube", AdapterSource.GENERIC_FALLBACK,
            // YouTube <= 20.x hosts the main UI in WatchWhileActivity. 21.x
            // launches the manifest entry com.google.android.youtube.app.honeycomb
            // .Shell$HomeActivity, but that wrapper instantiates
            // com.google.android.apps.youtube.app.watchwhile.MainActivity, which
            // owns the full-width "pivot_bar" HorizontalScrollView of Buttons.
            activityHints = setOf(
                "com.google.android.apps.youtube.app.WatchWhileActivity",
                "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity",
                "com.google.android.apps.youtube.app.watchwhile.MainActivity",
            ),
            idHints = setOf("bottom_navigation", "navigation_bar", "tab_bar", "pivot_bar"),
            classHints = setOf("BottomNavigationView", "NavigationBarView", "WatchWhileActivity", "PivotBar"),
            textHints = setOf("首页", "Home", "Shorts", "发布", "Create", "订阅内容", "Subscriptions", "通知", "Notifications", "你", "You", "我"),
            // 21.x moved creation to the top toolbar; the bottom row is five
            // regular tabs (Home/Shorts/Subscriptions/Notifications/You), so no
            // entry may be marked non-selectable.
            preferredSlots = 5..5, supportsPostButton = false,
            defaultAccentColor = 0xFFFF0000.toInt(), glassAccentColor = 0xFFFF0000.toInt(),
            sceneVisibilityAnimation = true,
            entryLabels = listOf("首页", "Shorts", "订阅内容", "通知", "你"))
