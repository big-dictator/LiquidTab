package io.github.offlineglass.hook.adapters.xianyu

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("xianyu", "com.taobao.idlefish", "闲鱼", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf(
                "com.taobao.fleamarket.home.activity.MainActivity",
                "com.taobao.fleamarket.home.activity.InitActivity",
            ),
            idHints = setOf("id_indicator", "indicator_itmes", "indicator_item_container"),
            classHints = setOf("MainActivity", "FrameLayout", "RelativeLayout", "LinearLayout"),
            textHints = setOf("闲鱼", "消息", "我的"),
            preferredSlots = 5..5, supportsPostButton = true,
            entryLabels = listOf("闲鱼", "会玩", "发布", "消息", "我的"),
            defaultAccentColor = 0xFFFFCD00.toInt(), drawsNavigationInOptics = true)
