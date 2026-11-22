package io.github.offlineglass.hook.topbar

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

/** Owns fixed-size channel interaction and glass lifecycle, not native page routing. */
internal object TopBarChannelHost {
    /** Preserve adapter-owned scene, tap handling and foreground projection. */
    fun createPanel(context: Context, config: GlassConfig, slots: Int,
        nonSelectableIndices: Set<Int> = emptySet(), nativeSelectionReliable: Boolean = true,
        navigationSource: ViewGroup? = null, sliderEnabled: Boolean,
        adapterOverride: TargetAdapter, isChannel: Boolean,
    ): GlassHostLayout {
        require(!isChannel || adapterOverride.indicatorInteractionPolicy ==
            io.github.offlineglass.rendering.IndicatorInteractionPolicy.FIXED_SIZE)
        return GlassHostLayout(context, config, slots, nonSelectableIndices,
            nativeSelectionReliable, navigationSource, sliderEnabled = sliderEnabled,
            adapterOverride = adapterOverride)
    }

    fun create(context: Context, config: GlassConfig, material: TopBarMaterialPolicy,
        slots: Int, scene: AppNavigationState, selected: () -> Int?, onSelect: (Int) -> Boolean,
    ): GlassHostLayout {
        require(material.role == TopBarRole.CHANNELS && slots > 1)
        val navigation = object : AppNavigationState by scene {
            override fun allowsHostTouch() = true
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) =
                selected()?.takeIf { it in 0 until slotCount }
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) =
                index in 0 until slotCount && onSelect(index)
        }
        val adapter = object : TargetAdapter {
            override val key = "shared-top-channels"
            override val ownsNavigationDrawing = true
            override val ownsNavigationTap = true
            override val managesHostSiblingOrder = true
            override val skipNavigationSnapshot = true
            override val hasPerFrameScene = true
            override val allowRenderNodeRefresh = true
            override val skipBackdropStableDelay = true
            override val allowsSystemBackgroundBlur = false
            override fun createNavigationState(context: Context) = navigation
            override fun normalizeConfig(config: GlassConfig) = material.apply(config)
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
        }
        return createPanel(context, config, slots, sliderEnabled = true,
            adapterOverride = adapter, isChannel = true).apply {
            isClickable = true
            isFocusable = false
            elevation = 0f
        }
    }
}
