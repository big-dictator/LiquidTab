package io.github.offlineglass.hook.adapters.mi_notes

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_notes", "com.miui.notes", "笔记", AdapterSource.DEDICATED,
            activityHints = setOf("com.miui.notes.ui.NotesListActivity"),
            idHints = setOf("bottom_navigation", "tab"), classHints = setOf("miuix.navigator.bottomnavigation.BottomNavigationView", "NotesListActivity"),
            textHints = setOf("笔记", "待办"), preferredSlots = 2..2, hookSignals = hookSignals())
