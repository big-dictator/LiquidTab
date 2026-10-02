package io.github.offlineglass.hook.adapters.mi_file_manager

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Main navigation and multi-select action bar are distinct native owners. */
internal object FileManagerAdapter : TargetAdapter {
    override val key = "mi_file_manager"
    override val ownsNavigationFinding = true

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            AdapterNavigationSearch.resourceEntryName(view) in IDS &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        } ?: return null
        if (AdapterNavigationSearch.resourceEntryName(navigation) == "split_action_bar") {
            val pill = (0 until navigation.childCount)
                .mapNotNull { navigation.getChildAt(it) as? ViewGroup }
                .firstOrNull { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            return NavigationCandidate(navigation, pill?.childCount?.coerceIn(2, 7) ?: 4, 1_000)
        }
        return NavigationCandidate(navigation, 3, 1_000)
    }

    private val IDS = setOf("split_action_bar", "bottom_navigation_container")
}
