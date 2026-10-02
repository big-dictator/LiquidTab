package io.github.offlineglass.hook.adapters.mi_theme

import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.hook.adapters.commonMiuixSignals

internal fun hookSignals(): List<TargetHookSignal> = listOf(
            TargetHookSignal("com.miui.thememanagerstore.ThemeResourceTabActivity", listOf("onCreate")),
            // The store delegates its home UI to the system theme manager
            // process (alias package com.android.thememanager); the real bar
            // lives under this proxy activity there.
            TargetHookSignal("com.android.thememanager.ThemeResourceProxyTabActivity", listOf("onCreate")),
        )
