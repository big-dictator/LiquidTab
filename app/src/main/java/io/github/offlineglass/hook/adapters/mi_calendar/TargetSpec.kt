package io.github.offlineglass.hook.adapters.mi_calendar

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_calendar", "com.android.calendar", "系统日历", AdapterSource.DEDICATED,
            activityHints = setOf("com.android.calendar.homepage.AllInOneActivity"),
            idHints = setOf("bottom_navigator", "bottom_navigation"), classHints = setOf("com.android.calendar.homepage.BottomNavigatorView", "AllInOneActivity"),
            textHints = setOf("年", "月", "周", "日"), preferredSlots = 4..4, hookSignals = hookSignals())
