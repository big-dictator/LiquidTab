package io.github.offlineglass.hook.adapters.jd

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("jd", "com.jingdong.app.mall", "京东", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf(
                "com.jingdong.app.mall.MainFrameActivity",
            ),
            idHints = setOf("tg", "fl"),
            classHints = setOf("MainFrameActivity", "LinearLayout", "FrameLayout"),
            textHints = setOf("首页", "逛", "消息", "购物车", "我的"),
            preferredSlots = 5..5, supportsPostButton = false,
            defaultAccentColor = 0xFFE2231A.toInt(), entryLabels = listOf("首页", "逛", "消息", "购物车", "我的"), drawsNavigationInOptics = true, sceneVisibilityAnimation = true)
