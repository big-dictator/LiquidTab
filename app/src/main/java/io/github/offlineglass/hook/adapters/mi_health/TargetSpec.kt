package io.github.offlineglass.hook.adapters.mi_health

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_health", "com.mi.health", "小米运动健康", AdapterSource.DEDICATED,
            activityHints = setOf("com.xiaomi.fitness.main.MainActivity"),
            idHints = setOf("main_tl_bottom"),
            classHints = setOf("com.google.android.material.tabs.TabLayout", "MainActivity"),
            textHints = setOf("健康", "运动", "设备", "我的"), preferredSlots = 4..4,
            defaultAccentColor = -38656, entryLabels = listOf("健康", "运动", "设备", "我的"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFF6900.toInt(), sceneVisibilityAnimation = true)
