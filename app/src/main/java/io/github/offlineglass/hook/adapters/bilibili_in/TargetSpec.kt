package io.github.offlineglass.hook.adapters.bilibili_in

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec

// Bilibili International (com.bilibili.app.in) shares the CN app's
// MainActivityV2 but renders its bottom bar as a ComposeView with
// resource id "tab_host": four plain tabs (首页/关注/消息/我的), no
// publish button and no shop entry. The native row exposes no View
// selection state, so the adapter owns selection itself and forwards
// taps as synthesized MotionEvents dispatched onto the ComposeView.
internal val targetSpec: TargetSpec = TargetSpec("bilibili_in", "com.bilibili.app.in", "哔哩哔哩国际版", AdapterSource.DEDICATED,
            activityHints = setOf("tv.danmaku.bili.MainActivityV2"),
            idHints = setOf("tab_host"),
            classHints = setOf("MainActivityV2"),
            textHints = setOf("首页", "关注", "消息", "我的"),
            preferredSlots = 4..4,
            entryLabels = listOf("首页", "关注", "消息", "我的"),
            defaultAccentColor = 0xFFFB7299.toInt())
