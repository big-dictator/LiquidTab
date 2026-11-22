package io.github.offlineglass.hook.adapters.meituan_main

import android.view.View
import android.view.ViewGroup
import android.content.Context
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AppNativeChromeController
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.SLOT_COUNT
import io.github.offlineglass.hook.adapters.meituan_main.MeituanMainTabContract.VIDEO

/** The real Meituan tab row is nested under TabBlockV2. */
internal object MeituanMainAdapter : TargetAdapter {
    override val key = "meituan_main"
    override val concentricIndicatorExpansion = true
    override val concentricIndicatorExpansionGain = 1.6f
    override val activeIndicatorDispersion = 0.40f
    override val forceLightMode = true
    override val hasPerFrameScene = true
    override val updatesSceneDuringTapTransition = true
    override val nativeSelectionReliable = true
    override val ownsNavigationDrawing = true
    override val skipNavigationSnapshot = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override val foregroundMaskInsetDp = 0f
    override val ownsNavigationTap = true
    override val managesNativeTabVisibility = true
    override val commitSelectionAfterTap = true
    override val accentColor: Int? = 0xFFFFC300.toInt()
    override val reuseOpticalBlur = true
    // Native routing is immediate; optical input follows the committed native page.
    override val keepsBackdropDuringTapTransition = true
    override val skipBackdropStableDelay = true
    override val captureDuringLiveBackdrop = false
    override val hideRedrawnNavigationSource = true
    override val projectedNavigationScale = 1f
    override val sourceCenterActionIndex = 2
    override val suppressPlatformElevation = true
    override val continuousIndicatorMask = true
    override fun normalizeConfig(config: io.github.offlineglass.config.GlassConfig) =
        config.copy(themeMode = io.github.offlineglass.config.GlassConfig.THEME_LIGHT, hiddenMask = config.hiddenMask or (1 shl VIDEO), detachedPublish = config.meituanDetachedXiaotuan)
    override fun barLayout(config: io.github.offlineglass.config.GlassConfig, width: Int,
        height: Int, density: Float, slots: Int): io.github.offlineglass.hook.adapters.AdapterBarLayout? {
        if (!config.detachedPublish || slots != SLOT_COUNT || config.hiddenMask and 4 != 0 ||
            height <= 0 || width <= height + 12f * density) return null
        val navigation = listOf(0, 3, 4).filter { config.hiddenMask and (1 shl it) == 0 }
        if (navigation.isEmpty()) return null
        val actionLeft = width - height.toFloat()
        return io.github.offlineglass.hook.adapters.AdapterBarLayout(
            mainWidth = actionLeft - 12f * density, actionLeft = actionLeft,
            actionSize = height.toFloat(), actionIndex = 2, navigationIndices = navigation,
        )
    }
    override val sourceDefaultTranslationDp = -6f
    override val sourceCenterTranslationDp = -2f
    override val sourceCenterClipRatio = 0.7f
    override val preserveNativeColorIndices = setOf(2)
    override val selectionSettleMs = 220L
    override fun scrollStopHideTab(selectedIndex: Int): Boolean = false
    override fun createNavigationState(context: Context): AppNavigationState = MeituanMainNavigationState()
    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState =
        MeituanMainNavigationState(host)
    override fun resetScrollStopHideOnOtherTabs(): Boolean = true
    override fun scrollStopHideDelayMs(selectedIndex: Int): Long = when (selectedIndex) {
        3 -> 800L
        4 -> 1_500L
        else -> 1_000L
    }
    override fun createNativeChromeController(): AppNativeChromeController = MeituanMainNativeChrome()
    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        MeituanMainNativeTabs.isNavigationRow(navigation)
    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        SLOT_COUNT.takeIf { MeituanMainNativeTabs.isNavigationRow(group) }
    override fun adjustNavigationScore(view: ViewGroup, idName: String?, score: Int): Int =
        if (idName == "bottom_tab_block" || MeituanMainNativeTabs.isNavigationRow(view)) score else 0
    override fun resolveNestedNavigation(navigation: ViewGroup): Pair<ViewGroup, Int>? {
        val row = MeituanMainNativeTabs.row(navigation) ?: return null
        return row to SLOT_COUNT
    }
}

