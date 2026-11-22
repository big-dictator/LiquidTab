package io.github.offlineglass.hook.adapters.xhs

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.topbar.TopBarChannelHost
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

/** Shared renderer, XHS-owned scene filtering. No renderer or other target changes. */
internal object XhsTopMaterial {
    fun create(context: Context, config: GlassConfig, scene: ViewGroup, backdrop: XhsBackdropScene,
               slots: Int = 1, panel: Boolean = false, panelCorners: Float = 14f,
               selected: () -> Int = { 0 }, onSelect: ((Int) -> Boolean)? = null): GlassHostLayout {
        val state = object : AppNavigationState {
            override val allowsSoftwareBackdropCapture = false
            override val backdropSceneViewsAreDescendants = true
            // DecorView.parent is ViewRootImpl, not ViewGroup. The shared renderer
            // requires a real parent container to record the explicit descendant scene.
            override fun resolveBackdropScene(host: View): View = scene.findViewById<View>(android.R.id.content) ?: scene
            override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
                return backdrop.views()
            }
            override fun allowsHostTouch() = onSelect != null
            override fun resolveAutoDarkMode(host: View) = XhsTopTheme.dark(context)
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = onSelect?.invoke(index) == true
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = selected().coerceIn(0, slotCount - 1)
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = true
            override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
            override fun suppressRedrawnSource() = false
            override fun forceNavigationBarImmersed(host: GlassHostLayout) = false
            override fun pauseBackdropWork(host: View) = host.windowVisibility != View.VISIBLE || !host.isShown
        }
        val material = TopBarMaterialPolicy(
            role = if (panel) TopBarRole.MENU else if (slots > 1) TopBarRole.CHANNELS else TopBarRole.ACTION,
            cornerRadiusPercent = if (panel) panelCorners else 50f,
            iconOnly = true, selectedAccent = slots > 1, accentColor = 0xFFFF2442.toInt(),
        )
        if (slots > 1 && !panel && onSelect != null) {
            return TopBarChannelHost.create(context, config, material, slots, state, selected, onSelect).apply {
                contentDescription = "小红书顶栏液态玻璃"
            }
        }
        val adapter = object : TargetAdapter {
            override val key = "xhs-top-material"
            override val ownsNavigationDrawing = true
            override val ownsNavigationTap = onSelect != null
            override val managesHostSiblingOrder = true
            override val skipNavigationSnapshot = true
            override val hasPerFrameScene = true
            override val allowRenderNodeRefresh = true
            override val skipBackdropStableDelay = true
            override val allowsSystemBackgroundBlur = false
            override fun createNavigationState(context: Context) = state
            override fun normalizeConfig(config: GlassConfig) = material.apply(config)
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
        }
        return GlassHostLayout(context, config, slots, nativeSelectionReliable = slots > 1,
            navigationSource = null, sliderEnabled = slots > 1, adapterOverride = adapter).apply {
            isClickable = onSelect != null; isFocusable = false; elevation = 0f
            contentDescription = "小红书顶栏液态玻璃"
        }
    }
}
