package io.github.offlineglass.hook.adapters.youtube

import io.github.offlineglass.targets.AdapterSource
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
            // Five stable settings positions also support older native rows.
            // Current native availability is resolved from the live pivot cells.
            preferredSlots = 5..5, supportsPostButton = true,
            defaultAccentColor = 0xFFFF0033.toInt(), glassAccentColor = 0xFFFF0033.toInt(),
            sceneVisibilityAnimation = true,
            defaultDetachedPublish = true,
            // Leave the historical Subscriptions bit intact for existing settings.
            entryLabels = listOf("首页", "Shorts", "创作", "", "我"))
