package io.github.offlineglass.hook.adapters.amap

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetHookSignal
import io.github.offlineglass.targets.TargetSpec

/** Amap LiteTabBar and its separate POI floating action bar. */
internal object AmapAdapter : TargetAdapter {
    override val key = "amap"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val reverseVisualSlotOrder = true
    override val actionOnlyIndices = setOf(2)
    override val floatingPillUsesConfiguredHeight = true
    override val floatingPillUsesSharedInsets = true
    override fun createNavigationState(context: Context): AppNavigationState = AmapNavigationState(context)
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        runCatching { AmapChrome.suppress(navigation) }
        navigation.post { runCatching { AmapChrome.suppress(navigation) } }
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            view.javaClass.name == "com.autonavi.bundle.uitemplate.tab.LiteTabBar" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        } ?: return null
        val cellHost = (0 until navigation.childCount)
            .mapNotNull { navigation.getChildAt(it) as? ViewGroup }
            .maxByOrNull { it.childCount }
            ?: navigation
        val cells = (0 until cellHost.childCount).count {
            val child = cellHost.getChildAt(it)
            child.isShown && child.width > 0 && child.height > 0
        }
        val slots = if (cells in 2..7) cells else cellHost.childCount.coerceIn(2, 7)
        return NavigationCandidate(navigation, slots, 1_000)
    }

    override fun hookSignals() = listOf(
        TargetHookSignal("com.autonavi.bundle.uitemplate.tab.LiteTabBar", listOf("onAttachedToWindow", "updateThemeMode")),
        TargetHookSignal("com.autonavi.bundle.amaphome.page.BootHomeTabPage", listOf("showTab")),
    )

    override fun beforeNavigationScan(activity: Activity) {
        AmapFloatingActionController.install(activity)
    }
}
