package io.github.offlineglass.hook.adapters.system_picker

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec

// The system "use app to open / share" chooser card. Unlike the app
// targets this spec carries no liquid bar adapter: it only drives a
// lightweight hook (HookEntry) that keeps the card's bottom gesture
// bar seated against the screen instead of letting it float/climb, and
// it exposes a module switch purely for development testing of that
// single behaviour. AOSP 14 hosts the chooser as com.android.intent
// .resolver; legacy Android 12/13 and several ROMs use
// com.android.permissioncontroller instead, so list them as aliases.
internal val targetSpec: TargetSpec = TargetSpec("system_picker", "com.android.intentresolver", "系统选择器(选择应用弹窗)", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf(
                "com.android.intentresolver.ui.ChooserActivity",
                "com.android.intentresolver.ui.ResolveActivity",
                "com.android.permissioncontroller.chooser.ui.ChoserActivity",
            ),
            aliasPackages = setOf(
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
            ),
            preferredSlots = 0..0,
            defaultIconOnly = true)
