package io.github.offlineglass.hook.adapters.meituan

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Server-driven TitleIndicator must have its complete native tab set. */
internal object MeituanTakeoutAdapter : TargetAdapter {
    override val key = "meituan"
    override val ownsNavigationFinding = true
    override val hasPerFrameScene = true
    override fun createNavigationState(context: Context) = MeituanTakeoutNavigationState()

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? {
        if (group.javaClass.name != TITLE_INDICATOR) return null
        val count = runCatching {
            group.javaClass.getMethod("getTabCount").invoke(group) as? Int
        }.getOrNull() ?: group.childCount
        Log.i("WmGlassDiag", "estimateSlotCount: TitleIndicator getTabCount=$count childCount=${group.childCount}")
        return if (count in spec.preferredSlots) count else 0
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            view.javaClass.name == TITLE_INDICATOR &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        } ?: return null
        val settled = estimateSlotCount(navigation, spec) ?: 0
        Log.i("WmGlassDiag", "find: TitleIndicator settledSlots=$settled childCount=${navigation.childCount}")
        if (settled < 2) return null
        return NavigationCandidate(navigation, settled, 1_000)
    }

    private const val TITLE_INDICATOR =
        "com.sankuai.waimai.business.page.homepage.view.TitleIndicator"
}
