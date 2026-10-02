package io.github.offlineglass.hook.adapters.meituan_main

import android.view.View
import android.view.ViewGroup
import android.content.Context
import io.github.offlineglass.hook.adapters.AppNativeChromeController
import io.github.offlineglass.hook.adapters.TargetAdapter

/** The real Meituan tab row is nested under TabBlockV2. */
internal object MeituanMainAdapter : TargetAdapter {
    override val key = "meituan_main"
    override val forceLightMode = true
    override val hasPerFrameScene = true
    override val reuseOpticalBlur = true
    override val captureDuringLiveBackdrop = false
    override val hideRedrawnNavigationSource = true
    override val projectedNavigationScale = 1f
    override val sourceCenterActionIndex = 2
    override val sourceDefaultTranslationDp = -6f
    override val sourceCenterTranslationDp = -2f
    override val sourceCenterClipRatio = 0.7f
    override val preserveNativeColorIndices = setOf(2)
    override val selectionSettleMs = 220L
    override fun scrollStopHideTab(selectedIndex: Int): Boolean = selectedIndex == 4
    override fun createNavigationState(context: Context) = MeituanMainNavigationState()
    override fun resetScrollStopHideOnOtherTabs(): Boolean = true
    override fun scrollStopHideDelayMs(selectedIndex: Int): Long = when (selectedIndex) {
        3 -> 800L
        4 -> 1_500L
        else -> 1_000L
    }
    override fun createNativeChromeController(): AppNativeChromeController = MeituanMainNativeChrome()
    override fun resolveNestedNavigation(navigation: ViewGroup): Pair<ViewGroup, Int>? {
        if (navigation.javaClass.name != "com.meituan.android.pt.homepage.tab.TabBlockV2") return null
        for (index in 0 until navigation.childCount) {
            val row = navigation.getChildAt(index) as? ViewGroup ?: continue
            if (!row.javaClass.name.endsWith(".PTLinearLayout")) continue
            val visible = (0 until row.childCount).count { childIndex ->
                val child = row.getChildAt(childIndex)
                child.visibility == View.VISIBLE && child.width > 0 && child.height > 0
            }
            if (visible in 3..6 && row.width >= navigation.width * 0.8f) return row to visible
        }
        return null
    }
}
