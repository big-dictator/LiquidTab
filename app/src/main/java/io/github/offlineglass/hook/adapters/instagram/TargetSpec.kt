package io.github.offlineglass.hook.adapters.instagram

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

// Instagram 451.0.0.53.80 (versionCode 385713116) 的主界面在 manifest 中声明为
// com.instagram.android.activity.MainTabActivity，但那只是 activity-alias（供
// .kpop/.floral/.flame 换图标别名复用）；真实实现类是
// com.instagram.mainactivity.InstagramMainActivity（dumpsys ActivityRecord 的
// mActivityComponent 证实）。matchesActivity 校验的是运行时类名，必须以真实类
// 为准，否则每次 resume 都被过滤、扫描器永不启动。旧名保留以兼容直连旧版。
// 底部导航为原生 LinearLayout(com.instagram.android:id/tab_bar)：5 个 clickable
// 的 FrameLayout tab（feed_tab / clips_tab / direct_tab / search_tab /
// profile_tab），选中态由 View.selected 表达；图标是 tab_icon ImageView
// （个人主页为 tab_avatar 头像）。tab_bar 上方有 1px 的 tab_bar_shadow
// 分隔线。适配沿用通用原生采集：底栏按 tab_bar 定位，图标走原生快照投影。
internal val targetSpec: TargetSpec = TargetSpec("instagram", "com.instagram.android", "Instagram", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.instagram.mainactivity.InstagramMainActivity",
                "com.instagram.android.activity.MainTabActivity",
            ),
            idHints = setOf("tab_bar"),
            classHints = setOf("MainTabActivity", "LinearLayout"),
            textHints = setOf("首页", "Reels", "消息", "搜索和探索", "主页", "Home", "Search", "Messages", "Profile"),
            preferredSlots = 5..5,
            defaultIconOnly = true,
            defaultAccentColor = 0xFFE1306C.toInt(),
            entryLabels = listOf("首页", "Reels", "消息", "搜索和探索", "主页"),
            sceneVisibilityAnimation = true)
