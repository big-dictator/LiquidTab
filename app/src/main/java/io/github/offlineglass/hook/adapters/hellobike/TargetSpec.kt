package io.github.offlineglass.hook.adapters.hellobike

import io.github.offlineglass.targets.AdapterSource
import io.github.offlineglass.targets.HideOption
import io.github.offlineglass.targets.TargetSpec


internal val targetSpec: TargetSpec = TargetSpec("hellobike", "com.jingyao.easybike", "哈啰", AdapterSource.GENERIC_FALLBACK,
            activityHints = setOf(
                "com.hellobike.atlas.business.portal.MainActivityFreeRideIcon",
                "com.hellobike.vvsmart.business.main.VVSmartMainActivity",
            ),
            idHints = setOf("register_tab_llt", "eventTabLayout", "tabIconContainer"),
            classHints = setOf("MainActivityFreeRideIcon", "LinearLayout", "RelativeLayout"),
            textHints = setOf("首页", "车主", "扫一扫", "消息", "我的"),
            preferredSlots = 5..5, supportsPostButton = true,
            defaultAccentColor = 0xFF0095FF.toInt())
