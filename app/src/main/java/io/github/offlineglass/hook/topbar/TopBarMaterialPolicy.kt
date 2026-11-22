package io.github.offlineglass.hook.topbar

import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.rendering.IndicatorInteractionPolicy

/** Semantic component roles, not native child positions or application identities. */
internal enum class TopBarRole { CHANNELS, ACTION, SEARCH, MENU }

/**
 * Common appearance boundary for reconstructed top chrome.
 *
 * Adapters supply only their existing layout/branding choices. Optical settings
 * (blur, tint alpha, smoothing, dispersion, posture) remain the live GlassConfig
 * values. This is deliberately not GlassConfig.normalized(): its minimum bar
 * height belongs to bottom navigation, not these 40/44dp top controls.
 *
 * Retain one policy per component; applying a hot config allocates only the same
 * config copy that the old adapter normalizers allocated. No View/native binding,
 * package branching, sensor listener, capture, or animation loop belongs here.
 */
internal data class TopBarMaterialPolicy(
    val role: TopBarRole,
    val heightDp: Float = 40f,
    val cornerRadiusPercent: Float = if (role == TopBarRole.MENU) 20f else 50f,
    val iconOnly: Boolean = false,
    val selectedAccent: Boolean? = null,
    val accentColor: Int? = null,
) {
    /** Only channel selectors opt out. Actions/search/menu keep their own feedback. */
    val indicatorInteractionPolicy: IndicatorInteractionPolicy
        get() = if (role == TopBarRole.CHANNELS) IndicatorInteractionPolicy.FIXED_SIZE
            else IndicatorInteractionPolicy.EXPANDING

    init {
        require(heightDp.isFinite() && heightDp > 0f)
        require(cornerRadiusPercent.isFinite() && cornerRadiusPercent in 0f..50f)
    }

    fun apply(config: GlassConfig): GlassConfig = config.copy(
        detachedPublish = false,
        hiddenMask = 0,
        barHeight = heightDp,
        cornerRadiusPercent = cornerRadiusPercent,
        iconOnly = iconOnly,
        selectedAccent = selectedAccent ?: config.selectedAccent,
        customAccentEnabled = if (accentColor != null) true else config.customAccentEnabled,
        customAccentColor = accentColor ?: config.customAccentColor,
    )
}
