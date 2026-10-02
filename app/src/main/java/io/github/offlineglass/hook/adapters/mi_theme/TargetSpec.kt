package io.github.offlineglass.hook.adapters.mi_theme

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec

// Theme Store (com.miui.themestore) keeps the thememanager resource
// namespace: the bottom bar id is com.android.thememanager:id/bottom_navigator.
// The bar is a natively floating pill (首页/分类/每日精选/我的) inside a
// full-width container, matched by floatingPillKeys in GlassInstaller.
// The store hands its home UI to the system theme manager process
// (com.android.thememanager), which hosts the real bar: that package is
// an alias process sharing this spec and its config.
internal val targetSpec: TargetSpec = TargetSpec("mi_theme", "com.miui.themestore", "主题商店", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.miui.thememanagerstore.ThemeResourceTabActivity",
                "com.android.thememanager.ThemeResourceProxyTabActivity",
            ),
            idHints = setOf("bottom_navigator", "bottom_navigation"), classHints = setOf("android.widget.FrameLayout", "ThemeResourceTabActivity"),
            textHints = setOf("首页", "分类", "每日精选", "我的"), preferredSlots = 4..4,
            aliasPackages = setOf("com.android.thememanager"))
