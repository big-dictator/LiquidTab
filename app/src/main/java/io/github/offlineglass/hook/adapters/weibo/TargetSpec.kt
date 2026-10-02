package io.github.offlineglass.hook.adapters.weibo

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("weibo", "com.sina.weibo", "微博", AdapterSource.DEDICATED,
            // Decompiled from Weibo 16.8.2: the native bottom bar container is
            // main_radio (LinearLayout, id 0x7f0a232c) in maintabs.xml. The 1px
            // shadow is iv_bottom_shadow (ImageView, a sibling of main_radio).
            // Tab items are TabViewGroupV2 (FrameLayout) created by
            // com.sina.weibo.bottombar.g builder. Selection state does NOT use
            // Android's selected/activated; it manually swaps icons and colors
            // via WBXTabBarViewManager. Tab changes broadcast
            // com.sina.weibo.action.MAIN_TAB_CHANGED with tab_id extra.
            activityHints = setOf(
                "com.sina.weibo.MainTabActivity",
                "com.sina.weibo.MainActivity",
                "com.sina.weibo.SplashActivity",
            ),
            idHints = setOf("main_radio", "main_theme_radio", "bottom_navigation", "tab_bar", "tabbar", "navigation_bar", "bottom_tab"),
            classHints = setOf("MainTabActivity", "TabViewGroupV2", "LinearLayout", "BottomNavigation", "NavigationBar"),
            textHints = setOf("首页", "视频", "发现", "消息", "我", "我的"),
            preferredSlots = 5..5, defaultAccentColor = 0xFFE6162D.toInt(), entryLabels = listOf("首页", "视频", "发现", "消息", "我"), glassAccentColor = 0xFFFF8200.toInt(), sceneVisibilityAnimation = true)
