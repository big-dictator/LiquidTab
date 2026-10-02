package io.github.offlineglass.hook.adapters.mi_gallery

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("mi_gallery", "com.miui.gallery", "相册", AdapterSource.DEDICATED,
            activityHints = setOf("com.miui.gallery.activity.HomePageActivity"),
            idHints = setOf("bottom_navigation", "home_navigation"), classHints = setOf("miuix.navigator.bottomnavigation.BottomNavigationView", "HomeNavigatorActivity", "HomePageActivity"),
            textHints = setOf("照片", "影集"), preferredSlots = 3..3)
