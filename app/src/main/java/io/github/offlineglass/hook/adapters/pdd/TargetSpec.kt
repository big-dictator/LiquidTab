package io.github.offlineglass.hook.adapters.pdd

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("pdd", "com.xunmeng.pinduoduo", "拼多多", AdapterSource.DEDICATED,
            activityHints = setOf("com.xunmeng.pinduoduo.ui.activity.MainFrameActivity"),
            idHints = setOf("bottom_navigation", "pdd_tab"),
            classHints = setOf("com.xunmeng.pinduoduo.ui_home_activity.widget.tab.PddTabView", "MainFrameActivity"),
            textHints = setOf("首页", "直播", "领券", "消息", "我的"), preferredSlots = 5..5,
            hideOptions = listOf(HideOption("隐藏直播", 1), HideOption("隐藏领券", 2)), entryLabels = listOf("首页", "多多视频", "大促狂降价", "聊天", "个人中心"), hookSignals = hookSignals(), drawsNavigationInOptics = true, glassAccentColor = 0xFFE02E24.toInt(), sceneVisibilityAnimation = true)
