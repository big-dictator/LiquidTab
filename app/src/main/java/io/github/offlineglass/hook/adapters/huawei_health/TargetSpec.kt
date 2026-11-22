package io.github.offlineglass.hook.adapters.huawei_health

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec(
    "huawei_health",
    "com.huawei.health",
    "华为运动健康",
    AdapterSource.DEDICATED,
    activityHints = setOf("com.huawei.health.MainActivity"),
    classHints = setOf(
        "com.huawei.ui.commonui.scrollview.HealthBottomView",
        "com.huawei.uikit.phone.hwbottomnavigationview.widget.HwBottomNavigationView",
    ),
    idHints = setOf("hw_main_tabs", "hw_health_main_viewpager"),
    preferredSlots = 2..7,
    defaultAccentColor = 0xFF00A392.toInt(),
    glassAccentColor = 0xFF00A392.toInt(),
    entryLabels = listOf("首页", "运动", "健康", "我的"),
)