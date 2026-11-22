package io.github.offlineglass.hook.adapters.x

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.TargetSpec

// X (Twitter) 的主界面 (com.x.android.main.MainActivity) 是全屏 Compose
// (MainLanding + Decompose) 架构：底栏是 Compose 组件，没有任何原生
// per-tab View。适配方案：模块注入自有 shim 容器作为 navigation source
// 兼原生底栏遮盖条，宿主用 XRenderer 自绘 5 个官方 tab 图标（Home /
// Search / Grok / Notifications / Messages），点击以原生切页回调
// 直接执行。X 不用蓝色高亮选中 tab，选中态为图标
// filled 变体 + 反色。
internal val targetSpec: TargetSpec = TargetSpec("x", "com.twitter.android", "X", AdapterSource.DEDICATED,
            activityHints = setOf("com.x.android.main.MainActivity"),
            preferredSlots = 5..5,
            defaultIconOnly = true,
            defaultAccentColor = 0xFF1D9BF0.toInt(),
            entryLabels = listOf("Home", "Search", "Grok", "Notifications", "Messages"),
            // 原生底栏有滚动显隐节奏（语义识别的 overlay 入口）：跟随切换时玻璃底栏渐隐/渐显。
            sceneVisibilityAnimation = true)


