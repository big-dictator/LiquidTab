package io.github.offlineglass.hook.adapters.mi_file_manager

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_file_manager", "com.android.fileexplorer", "文件管理", AdapterSource.DEDICATED,
            activityHints = setOf("com.android.fileexplorer.FileExplorerTabActivity", "com.android.fileexplorer.activity.PrivateFolderActivity"),
            idHints = setOf("bottom_navigation", "tab"), classHints = setOf("miuix.navigator.bottomnavigation.BottomNavigationView", "FileExplorerTabActivity"),
            textHints = setOf("最近", "浏览", "云盘"), preferredSlots = 3..3,
            hideOptions = listOf(HideOption("隐藏云盘", 2)))
