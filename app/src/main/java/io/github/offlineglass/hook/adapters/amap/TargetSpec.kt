package io.github.offlineglass.hook.adapters.amap

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec

// Amap home tab bar is a self-drawn LiteTabBar (extends DtLinearLayout)
// whose five cells are TabItemLayoutV2 (icon + label + badge). The pill
// is natively floating: 60dp tall with 14dp side margins and a bottom
// gap, so it reuses the Theme-Store floating-pill geometry.
internal val targetSpec: TargetSpec = TargetSpec("amap", "com.autonavi.minimap", "高德地图", AdapterSource.DEDICATED,
            activityHints = setOf(
                "com.autonavi.bundle.amaphome.page.BootHomeTabPage",
                "com.autonavi.bundle.amaphome.page.MapHomeTabPage",
                // Amap hosts the home pill inside NewMapActivity (extends
                // WingActivity); without this hint the generic resume->scan
                // path is skipped and the bar relied only on the fragile
                // LiteTabBar signal.
                "NewMapActivity",
            ),
            idHints = setOf("tab_container", "tab_bar", "tab_host"),
            classHints = setOf(
                "com.autonavi.bundle.uitemplate.tab.LiteTabBar",
                "com.autonavi.minimap.tabspage.base.C3TabBar",
                "BootHomeTabPage",
            ),
            textHints = setOf("首页", "附近", "消息", "打车", "我的"),
            preferredSlots = 5..5,
            defaultAccentColor = 0xFF0091FF.toInt(), entryLabels = listOf("首页", "附近", "消息", "打车", "我的"))
