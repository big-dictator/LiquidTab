package io.github.offlineglass.hook.adapters.mi_phone

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_phone", "com.android.contacts", "电话与联系人", AdapterSource.DEDICATED,
            activityHints = setOf("com.android.contacts.activities.TwelveKeyDialer", "com.android.contacts.activities.DialtactsActivity", "com.android.contacts.activities.PeopleActivity", "com.android.contacts.activities.MainActivity", "com.android.contacts.ui.MainActivity"),
            idHints = setOf("bottom_navigation", "tabs"), classHints = setOf("miuix.navigator.bottomnavigation.BottomNavigationView", "DialpadLayout"),
            textHints = setOf("通话", "联系人"), preferredSlots = 2..2, hookSignals = hookSignals())
