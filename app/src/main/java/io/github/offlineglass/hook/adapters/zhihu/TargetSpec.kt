package io.github.offlineglass.hook.adapters.zhihu

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

internal val targetSpec = TargetSpec("zhihu", "com.zhihu.android", "知乎", AdapterSource.DEDICATED,
    activityHints = setOf("com.zhihu.android.app.ui.activity.MainActivity"),
    idHints = setOf("main_tab"),
    classHints = setOf("BottomNavMenuView"),
    preferredSlots = 2..6,
    // Actual native tab titles/order are reported by ZhihuNavigationState.
    defaultAccentColor = 0xFF0084FF.toInt(),
    sceneVisibilityAnimation = true)
