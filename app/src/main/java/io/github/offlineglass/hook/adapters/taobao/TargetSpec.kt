package io.github.offlineglass.hook.adapters.taobao

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("taobao", "com.taobao.taobao", "淘宝", AdapterSource.DEDICATED,
            activityHints = setOf("com.taobao.tao.welcome.Welcome"),
            idHints = setOf("tabs"),
            classHints = setOf("android.widget.TabWidget", "Welcome"),
            textHints = setOf("首页", "视频", "消息", "购物车", "我的淘宝"),
            preferredSlots = 5..5,
            defaultAccentColor = 0xFFFF5000.toInt(), entryLabels = listOf("首页", "视频", "消息", "购物车", "我的淘宝"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFFF5000.toInt(), sceneVisibilityAnimation = true)
