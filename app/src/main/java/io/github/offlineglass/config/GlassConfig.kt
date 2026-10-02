package io.github.offlineglass.config

import android.graphics.Color
import android.os.Bundle

/** Locally persisted appearance and behavior settings for the glass navigation bar. */
data class GlassConfig(
    val enabled: Boolean = true,
    val themeMode: Int = THEME_SYSTEM,
    val tabWidth: Float = 76f,
    val bottomPadding: Float = 12f,
    val blurRadius: Float = 2f,
    /** Corner radius as a percentage of the current bar/indicator height. */
    val cornerRadiusPercent: Float = 50f,
    val cornerSmoothing: Float = -1f,
    val barHeight: Float = 56f,
    val lightAlpha: Float = 0.40f,
    val darkAlpha: Float = 0.40f,
    /** Multiplier for the outer bar bloom stroke in dark mode only. */
    val darkBarHighlightStrength: Float = 0.5f,
    val iconScale: Float = 1.0f,
    val textSize: Float = 11f,
    val iconOnly: Boolean = false,
    val hiddenMask: Int = 0,
    val showPostButton: Boolean = true,
    val customAccentEnabled: Boolean = false,
    val customAccentColor: Int = DEFAULT_ACCENT,
    val showChannel: Boolean = false,
    val backdropCapture: Boolean = true,
    val nativeBlur: Boolean = false,
    val selectedAccent: Boolean = false,
    /** Global optical-effect gate. Navigation, blur and touch routing stay active. */
    val liquidGlassEnabled: Boolean = true,
    /** Global gate for page-bottom progressive blur veils. */
    val bottomGradientBlurEnabled: Boolean = false,
    val solidBarEnabled: Boolean = false,
    val outlineEnabled: Boolean = true,
    val classicNavigation: Boolean = false,
    /** Live device dark state, stamped by the module-side provider. */
    val systemDark: Boolean = false,
) {
    fun normalized(): GlassConfig = copy(
        themeMode = themeMode.coerceIn(THEME_SYSTEM, THEME_DARK),
        tabWidth = tabWidth.coerceIn(64f, 92f),
        bottomPadding = bottomPadding.coerceIn(4f, 28f),
        blurRadius = blurRadius.coerceIn(0f, 16f),
        cornerRadiusPercent = 50f,
        cornerSmoothing = -1f,
        barHeight = barHeight.coerceIn(56f, 64f),
        lightAlpha = 0.40f,
        darkAlpha = 0.40f,
        darkBarHighlightStrength = darkBarHighlightStrength.coerceIn(0.5f, 1.7f),
        // Per-app adapters such as QQ need a wider range because their source
        // navigation row is projected into a much narrower floating panel.
        iconScale = iconScale.coerceIn(0.70f, 1.40f),
        textSize = textSize.coerceIn(9f, 14f),
        customAccentColor = customAccentColor or Color.BLACK,
        bottomGradientBlurEnabled = false,
    )

    fun toBundle(packageName: String): Bundle = Bundle().apply {
        val value = normalized()
        putString(ConfigContract.KEY_PACKAGE, packageName)
        putBoolean(ConfigContract.KEY_ENABLED, value.enabled)
        putInt(ConfigContract.KEY_THEME_MODE, value.themeMode)
        putFloat(ConfigContract.KEY_TAB_WIDTH, value.tabWidth)
        putFloat(ConfigContract.KEY_BOTTOM_PADDING, value.bottomPadding)
        putFloat(ConfigContract.KEY_BLUR_RADIUS, value.blurRadius)
        putFloat(ConfigContract.KEY_CORNER_RADIUS_PERCENT, value.cornerRadiusPercent)
        putFloat(ConfigContract.KEY_CORNER_SMOOTHING, value.cornerSmoothing)
        putFloat(ConfigContract.KEY_BAR_HEIGHT, value.barHeight)
        putFloat(ConfigContract.KEY_LIGHT_ALPHA, value.lightAlpha)
        putFloat(ConfigContract.KEY_DARK_ALPHA, value.darkAlpha)
        putFloat(ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, value.darkBarHighlightStrength)
        putFloat(ConfigContract.KEY_ICON_SCALE, value.iconScale)
        putFloat(ConfigContract.KEY_TEXT_SIZE, value.textSize)
        putBoolean(ConfigContract.KEY_ICON_ONLY, value.iconOnly)
        putInt(ConfigContract.KEY_HIDDEN_MASK, value.hiddenMask)
        putBoolean(ConfigContract.KEY_SHOW_POST, value.showPostButton)
        putBoolean(ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, value.customAccentEnabled)
        putInt(ConfigContract.KEY_CUSTOM_ACCENT_COLOR, value.customAccentColor)
        putBoolean(ConfigContract.KEY_SHOW_CHANNEL, value.showChannel)
        putBoolean(ConfigContract.KEY_BACKDROP_CAPTURE, value.backdropCapture)
        putBoolean(ConfigContract.KEY_NATIVE_BLUR, value.nativeBlur)
        putBoolean(ConfigContract.KEY_SELECTED_ACCENT, value.selectedAccent)
        putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, value.liquidGlassEnabled)
        putBoolean(ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED, value.bottomGradientBlurEnabled)
        putBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, value.solidBarEnabled)
        putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, value.outlineEnabled)
        putBoolean(ConfigContract.KEY_CLASSIC_NAVIGATION, value.classicNavigation)
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
        const val DEFAULT_ACCENT: Int = -30208

        fun fromBundle(bundle: Bundle?): GlassConfig {
            if (bundle == null) return GlassConfig()
            return GlassConfig(
                enabled = bundle.getBoolean(ConfigContract.KEY_ENABLED, true),
                themeMode = bundle.getInt(ConfigContract.KEY_THEME_MODE, THEME_SYSTEM),
                tabWidth = bundle.getFloat(ConfigContract.KEY_TAB_WIDTH, 76f),
                bottomPadding = bundle.getFloat(ConfigContract.KEY_BOTTOM_PADDING, 12f),
                blurRadius = bundle.getFloat(ConfigContract.KEY_BLUR_RADIUS, 2f),
                cornerRadiusPercent = bundle.getFloat(ConfigContract.KEY_CORNER_RADIUS_PERCENT, 50f),
                cornerSmoothing = bundle.getFloat(ConfigContract.KEY_CORNER_SMOOTHING, -1f),
                barHeight = bundle.getFloat(ConfigContract.KEY_BAR_HEIGHT, 56f),
                lightAlpha = bundle.getFloat(ConfigContract.KEY_LIGHT_ALPHA, 0.40f),
                darkAlpha = bundle.getFloat(ConfigContract.KEY_DARK_ALPHA, 0.40f),
                darkBarHighlightStrength = bundle.getFloat(ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, 0.5f),
                iconScale = bundle.getFloat(ConfigContract.KEY_ICON_SCALE, 1f),
                textSize = bundle.getFloat(ConfigContract.KEY_TEXT_SIZE, 11f),
                iconOnly = bundle.getBoolean(ConfigContract.KEY_ICON_ONLY, false),
                hiddenMask = bundle.getInt(ConfigContract.KEY_HIDDEN_MASK, 0),
                showPostButton = bundle.getBoolean(ConfigContract.KEY_SHOW_POST, true),
                customAccentEnabled = bundle.getBoolean(ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, false),
                customAccentColor = bundle.getInt(ConfigContract.KEY_CUSTOM_ACCENT_COLOR, DEFAULT_ACCENT),
                showChannel = bundle.getBoolean(ConfigContract.KEY_SHOW_CHANNEL, false),
                backdropCapture = bundle.getBoolean(ConfigContract.KEY_BACKDROP_CAPTURE, true),
                nativeBlur = bundle.getBoolean(ConfigContract.KEY_NATIVE_BLUR, false),
                selectedAccent = bundle.getBoolean(ConfigContract.KEY_SELECTED_ACCENT, false),
                liquidGlassEnabled = bundle.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true),
                bottomGradientBlurEnabled = false,
                solidBarEnabled = bundle.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false),
                outlineEnabled = bundle.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true),
                classicNavigation = bundle.getBoolean(ConfigContract.KEY_CLASSIC_NAVIGATION, false),
                systemDark = bundle.getBoolean(ConfigContract.KEY_SYSTEM_DARK, false),
            ).normalized()
        }
    }
}
