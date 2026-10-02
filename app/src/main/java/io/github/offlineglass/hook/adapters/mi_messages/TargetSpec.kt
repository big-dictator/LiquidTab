package io.github.offlineglass.hook.adapters.mi_messages

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_messages", "com.android.mms", "短信", AdapterSource.DEDICATED,
            activityHints = setOf("com.android.mms.ui.MmsTabActivity"),
            idHints = setOf("bottom_navigation", "tab"), classHints = setOf("miuix.navigator.bottomnavigation.BottomNavigationView", "MmsTabActivity"),
            textHints = setOf("主要", "推广"), preferredSlots = 2..2, entryLabels = listOf("主要", "推广"), hookSignals = hookSignals())
