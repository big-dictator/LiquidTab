package io.github.offlineglass.hook.adapters.mihome

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mihome", "com.xiaomi.smarthome", "米家", AdapterSource.DEDICATED,
            activityHints = setOf("com.xiaomi.smarthome.SmartHomeMainActivity"),
            idHints = setOf("bottom_navigation", "tab_page_indicator"),
            classHints = setOf("com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew", "SmartHomeMainActivity"),
            textHints = setOf("米家", "智能", "产品", "商城", "我的"), preferredSlots = 4..5,
            hideOptions = listOf(HideOption("隐藏产品", 2), HideOption("隐藏商城", 3)), entryLabels = listOf("米家", "智能", "产品", "商城", "我的"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFF2587E8.toInt(), sceneVisibilityAnimation = true)
