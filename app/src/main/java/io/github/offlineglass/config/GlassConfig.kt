package io.github.offlineglass.config

import android.graphics.Color
import android.os.Bundle

/** Locally persisted appearance and behavior settings for the glass navigation bar. */
data class GlassConfig(
    val enabled: Boolean = true,
    val themeMode: Int = THEME_SYSTEM,
    val tabWidth: Float = 76f,
    val bottomPadding: Float = 12f,
    val blurRadius: Float = DEFAULT_BLUR_RADIUS,
    /** Corner radius as a percentage of the current bar/indicator height. */
    val cornerRadiusPercent: Float = 50f,
    val cornerSmoothing: Float = -1f,
    val barHeight: Float = DEFAULT_BAR_HEIGHT,
    val lightAlpha: Float = DEFAULT_BAR_ALPHA,
    val darkAlpha: Float = DEFAULT_BAR_ALPHA,
    /** Shared highlight outline strength in both themes. */
    val darkBarHighlightStrength: Float = DEFAULT_HIGHLIGHT_STRENGTH_PERCENT / 100f,
    val highlightTransparency: Float = DEFAULT_HIGHLIGHT_TRANSPARENCY_PERCENT / 100f,
    val iconScale: Float = 1.0f,
    val textSize: Float = 11f,
    val iconOnly: Boolean = false,
    val hiddenMask: Int = 0,
    val detachedPublish: Boolean = false,
    val meituanDetachedXiaotuan: Boolean = true,
    /** Domestic Bilibili home-only low-power edge treatments. */
    val bilibiliSolidTopGradient: Boolean = true,
    val bilibiliBottomColorGradient: Boolean = true,
    val bilibiliTopBar: Boolean = true,
    val bilibiliEdgeToEdgeFeed: Boolean = false,
    val bilibiliInDetachedSearch: Boolean = false,
    val bilibiliDetachedSearch: Boolean = false,
    val bilibiliInVideoAmbient: Boolean = false,
    val bilibiliVideoAmbient: Boolean = false,
    val douyinTopBar: Boolean = true,
    val tiktokTopBar: Boolean = true,
    val xhsTopBar: Boolean = true,
    val xhsEdgeToEdgeFeed: Boolean = false,
    val weiboHomeTopBar: Boolean = true,
    /** Translate only adapter-painted bottom labels; native clicks/accessibility remain untouched. */
    val managerEnglishNavigationLabels: Boolean = false,
    val tiktokPinnedTabs: String = io.github.offlineglass.config.TikTokTopChannels.DEFAULT_PINS,
    val douyinPinnedTabs: String = DouyinTopChannels.DEFAULT_PINS,
    val tiktokTallVideoFill: Boolean = false,
    val showPostButton: Boolean = true,
    val customAccentEnabled: Boolean = false,
    val customAccentColor: Int = DEFAULT_ACCENT,
    val showChannel: Boolean = false,
    val backdropCapture: Boolean = true,
    val nativeBlur: Boolean = false,
    val selectedAccent: Boolean = false,
    /** Spotify-only song-colored mini-player progress surface. */
    val spotifyProgressFill: Boolean = true,
    val spotifyBarOpacity: Float = 1f,
    val shortVideoBarOpacity: Float = 1f,
    val wechatFoldPlanB: Boolean = false,
    val wechatTopBarReconstruction: Boolean = true,
    val wechatTopBlurGradient: Boolean = false,
    /** X-only：信息流滚动时是否自动隐藏底栏（帖子详情页始终隐藏）。 */
    val xAutoHideBar: Boolean = false,
    val miHealthSimplifyMine: Boolean = true,
    val miHealthIosIcons: Boolean = true,
    val spotifyThreeTabFullWidth: Boolean = true,
    val spotifyPremiumAvailable: Boolean = true,
    val spotifyCreateCommand: Boolean = false,
    /** Global optical-effect gate. Navigation, blur and touch routing stay active. */
    val liquidGlassEnabled: Boolean = true,
    /** Global gate for page-bottom progressive blur veils. */
    val bottomGradientBlurEnabled: Boolean = false,
    val solidBarEnabled: Boolean = false,
    val outlineEnabled: Boolean = true,
    val postureEffectsEnabled: Boolean = false,
    val indicatorInteractionLightEnabled: Boolean = true,
    val simplifyTabSwitch: Boolean = DEFAULT_SIMPLIFY_TAB_SWITCH,
    /** Shared liquid motion: low values rebound, high values resist flow. */
    val liquidViscosity: Float = 25f,
    val refractionStrength: Float = DEFAULT_REFRACTION_STRENGTH,
    val flowHorizontalStrength: Float = 100f,
    val flowVerticalStrength: Float = 100f,
    val classicNavigation: Boolean = false,
    val bottomBarIconOnly: Boolean = DEFAULT_BOTTOM_BAR_ICON_ONLY,
    /** Live device dark state, stamped by the module-side provider. */
    val systemDark: Boolean = false,
) {
    /** One physical tint alpha in both themes; RGB may still follow the theme. */
    fun surfaceTintColor(dark: Boolean): Int {
        val a = ((if (lightAlpha.isFinite()) lightAlpha else DEFAULT_BAR_ALPHA).coerceIn(0f, 0.8f) * 255f).toInt()
        return if (dark) Color.argb(a, 33, 31, 38) else Color.argb(a, 255, 255, 255)
    }

    fun withSurfaceOpacity(color: Int): Int = if (Color.alpha(color) == 0 || solidBarEnabled) color
        else (color and 0x00ffffff) or (Color.alpha(surfaceTintColor(false)) shl 24)

    val refractionScale: Float get() = refractionStrength / 100f
    val refractionPaddingScale: Float get() = refractionScale.coerceAtLeast(1f)

    @JvmOverloads
    fun isYouTubeCreateVisible(): Boolean =
        showPostButton && (hiddenMask and (1 shl 2)) == 0

    fun isTiebaPublishVisible(): Boolean =
        showPostButton && (hiddenMask and (1 shl 2)) == 0

    fun normalized(packageName: String = ""): GlassConfig = copy(
        bilibiliDetachedSearch = bilibiliDetachedSearch &&
            (packageName != "tv.danmaku.bili" || hiddenMask and 31 != 0),
        detachedPublish = detachedPublish &&
            (packageName != "com.baidu.tieba" || isTiebaPublishVisible()) &&
            (packageName != "com.google.android.youtube" || isYouTubeCreateVisible()) &&
            (packageName != "com.zhiliaoapp.musically" || hiddenMask and (1 shl 2) == 0) &&
            (packageName != "com.ss.android.ugc.aweme" || hiddenMask and (1 shl 2) == 0) &&
            (packageName != "com.xingin.xhs" || hiddenMask and (1 shl 2) == 0) &&
            (packageName != "com.taobao.idlefish" || hiddenMask and (1 shl 2) == 0) &&
            (packageName != "tv.danmaku.bili" ||
                (hiddenMask and (1 shl 2) == 0 && !(bilibiliDetachedSearch && hiddenMask and 31 != 0))),
        tiktokPinnedTabs = io.github.offlineglass.config.TikTokTopChannels.pins(tiktokPinnedTabs).joinToString("\n"),
        douyinPinnedTabs = DouyinTopChannels.pins(douyinPinnedTabs).joinToString("\n"),
        themeMode = themeMode.coerceIn(THEME_SYSTEM, THEME_DARK),
        tabWidth = tabWidth.coerceIn(64f, 92f),
        bottomPadding = bottomPadding.coerceIn(4f, 28f),
        blurRadius = blurRadius.coerceIn(0f, 16f),
        liquidViscosity = if (liquidViscosity.isFinite()) liquidViscosity.coerceIn(0f, 100f) else 25f,
        refractionStrength = if (refractionStrength.isFinite()) refractionStrength.coerceIn(0f, 200f) else DEFAULT_REFRACTION_STRENGTH,
        flowHorizontalStrength = if (flowHorizontalStrength.isFinite()) flowHorizontalStrength.coerceIn(0f, 100f) else 100f,
        flowVerticalStrength = if (flowVerticalStrength.isFinite()) flowVerticalStrength.coerceIn(0f, 100f) else 100f,
        spotifyBarOpacity = if (spotifyBarOpacity.isFinite()) spotifyBarOpacity.coerceIn(0f, 1f) else 1f,
        shortVideoBarOpacity = if (shortVideoBarOpacity.isFinite()) shortVideoBarOpacity.coerceIn(0f, 1f) else 1f,
        cornerRadiusPercent = 50f,
        cornerSmoothing = -1f,
        barHeight = barHeight.coerceIn(MIN_BAR_HEIGHT, MAX_BAR_HEIGHT),
        lightAlpha = if (lightAlpha.isFinite()) lightAlpha.coerceIn(0f, 0.80f) else DEFAULT_BAR_ALPHA,
        darkAlpha = if (lightAlpha.isFinite()) lightAlpha.coerceIn(0f, 0.80f) else DEFAULT_BAR_ALPHA,
        darkBarHighlightStrength = darkBarHighlightStrength.coerceIn(0f, 1.7f),
        highlightTransparency = highlightTransparency.coerceIn(0f, 1f),
        // Per-app adapters such as QQ need a wider range because their source
        // navigation row is projected into a much narrower floating panel.
        iconScale = iconScale.coerceIn(0.70f, 1.40f),
        textSize = textSize.coerceIn(9f, 14f),
        customAccentColor = customAccentColor or Color.BLACK,
        bottomGradientBlurEnabled = false,
    )

    fun toBundle(packageName: String): Bundle = Bundle().apply {
        val value = normalized(packageName)
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
        putFloat(ConfigContract.KEY_HIGHLIGHT_TRANSPARENCY, value.highlightTransparency)
        putFloat(ConfigContract.KEY_ICON_SCALE, value.iconScale)
        putFloat(ConfigContract.KEY_TEXT_SIZE, value.textSize)
        putBoolean(ConfigContract.KEY_ICON_ONLY, value.iconOnly)
        putInt(ConfigContract.KEY_HIDDEN_MASK, value.hiddenMask)
        putBoolean(ConfigContract.KEY_DETACHED_PUBLISH, value.detachedPublish)
        putBoolean(ConfigContract.KEY_MEITUAN_DETACHED_XIAOTUAN, value.meituanDetachedXiaotuan)
        putBoolean(ConfigContract.KEY_BILIBILI_SOLID_TOP_GRADIENT, value.bilibiliSolidTopGradient)
        putBoolean(ConfigContract.KEY_BILIBILI_BOTTOM_COLOR_GRADIENT, value.bilibiliBottomColorGradient)
        putBoolean(ConfigContract.KEY_BILIBILI_TOP_BAR, value.bilibiliTopBar)
        putBoolean(ConfigContract.KEY_BILIBILI_EDGE_TO_EDGE_FEED, value.bilibiliEdgeToEdgeFeed)
        putBoolean(ConfigContract.KEY_BILIBILI_IN_DETACHED_SEARCH, value.bilibiliInDetachedSearch)
        putBoolean(ConfigContract.KEY_BILIBILI_DETACHED_SEARCH, value.bilibiliDetachedSearch)
        putBoolean(ConfigContract.KEY_BILIBILI_IN_VIDEO_AMBIENT, value.bilibiliInVideoAmbient)
        putBoolean(ConfigContract.KEY_BILIBILI_VIDEO_AMBIENT, value.bilibiliVideoAmbient)
        putBoolean(ConfigContract.KEY_DOUYIN_TOP_BAR, value.douyinTopBar)
        putBoolean(ConfigContract.KEY_TIKTOK_TOP_BAR, value.tiktokTopBar)
        putBoolean(ConfigContract.KEY_XHS_TOP_BAR, value.xhsTopBar)
        putBoolean(ConfigContract.KEY_XHS_EDGE_TO_EDGE_FEED, value.xhsEdgeToEdgeFeed)
        putBoolean(ConfigContract.KEY_WEIBO_HOME_TOP_BAR, value.weiboHomeTopBar)
        putBoolean(ConfigContract.KEY_MANAGER_ENGLISH_LABELS, value.managerEnglishNavigationLabels)
        putString(ConfigContract.KEY_TIKTOK_PINNED_TABS, value.tiktokPinnedTabs)
        putString(ConfigContract.KEY_DOUYIN_PINNED_TABS, value.douyinPinnedTabs)
        putBoolean(ConfigContract.KEY_TIKTOK_TALL_VIDEO_FILL, value.tiktokTallVideoFill)
        putBoolean(ConfigContract.KEY_SHOW_POST, value.showPostButton)
        putBoolean(ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, value.customAccentEnabled)
        putInt(ConfigContract.KEY_CUSTOM_ACCENT_COLOR, value.customAccentColor)
        putBoolean(ConfigContract.KEY_SHOW_CHANNEL, value.showChannel)
        putBoolean(ConfigContract.KEY_BACKDROP_CAPTURE, value.backdropCapture)
        putBoolean(ConfigContract.KEY_NATIVE_BLUR, value.nativeBlur)
        putBoolean(ConfigContract.KEY_SELECTED_ACCENT, value.selectedAccent)
        putFloat(ConfigContract.KEY_SPOTIFY_BAR_OPACITY, value.spotifyBarOpacity)
        putFloat(ConfigContract.KEY_SHORT_VIDEO_BAR_OPACITY, value.shortVideoBarOpacity)
        putBoolean(ConfigContract.KEY_SPOTIFY_PROGRESS_FILL, value.spotifyProgressFill)
        putBoolean(ConfigContract.KEY_WECHAT_FOLD_PLAN_B, value.wechatFoldPlanB)
        putBoolean(ConfigContract.KEY_WECHAT_TOP_BAR_RECONSTRUCTION, value.wechatTopBarReconstruction)
        putBoolean(ConfigContract.KEY_WECHAT_TOP_BLUR_GRADIENT, value.wechatTopBlurGradient)
        putBoolean(ConfigContract.KEY_X_AUTO_HIDE_BAR, value.xAutoHideBar)
        putBoolean(ConfigContract.KEY_MI_HEALTH_SIMPLIFY_MINE, value.miHealthSimplifyMine)
        putBoolean(ConfigContract.KEY_MI_HEALTH_IOS_ICONS, value.miHealthIosIcons)
        putBoolean(ConfigContract.KEY_SPOTIFY_THREE_TAB_FULL_WIDTH, value.spotifyThreeTabFullWidth)
        putBoolean(ConfigContract.KEY_SPOTIFY_PREMIUM_AVAILABLE, value.spotifyPremiumAvailable)
        putBoolean(ConfigContract.KEY_SPOTIFY_CREATE_COMMAND, value.spotifyCreateCommand)
        putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, value.liquidGlassEnabled)
        putBoolean(ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED, value.bottomGradientBlurEnabled)
        putBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, value.solidBarEnabled)
        putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, value.outlineEnabled)
        putBoolean(ConfigContract.KEY_POSTURE_EFFECTS, value.postureEffectsEnabled)
        putBoolean(ConfigContract.KEY_INDICATOR_INTERACTION_LIGHT, value.indicatorInteractionLightEnabled)
        putBoolean(ConfigContract.KEY_SIMPLIFY_TAB_SWITCH, value.simplifyTabSwitch)
        putFloat(ConfigContract.KEY_LIQUID_VISCOSITY, value.liquidViscosity)
        putFloat(ConfigContract.KEY_REFRACTION_STRENGTH, value.refractionStrength)
        putFloat(ConfigContract.KEY_FLOW_HORIZONTAL, value.flowHorizontalStrength)
        putFloat(ConfigContract.KEY_FLOW_VERTICAL, value.flowVerticalStrength)
        putBoolean(ConfigContract.KEY_CLASSIC_NAVIGATION, value.classicNavigation)
        putBoolean(ConfigContract.KEY_BOTTOM_BAR_ICON_ONLY, value.bottomBarIconOnly)
    }

    companion object {
        const val DEFAULT_BAR_TRANSPARENCY_PERCENT = 50f
        const val DEFAULT_BLUR_RADIUS = 0f
        const val DEFAULT_BAR_HEIGHT = 56f
        const val DEFAULT_BAR_ALPHA = 1f - DEFAULT_BAR_TRANSPARENCY_PERCENT / 100f
        const val DEFAULT_HIGHLIGHT_STRENGTH_PERCENT = 60f
        const val DEFAULT_HIGHLIGHT_TRANSPARENCY_PERCENT = 45f
        const val DEFAULT_SIMPLIFY_TAB_SWITCH = false
        const val DEFAULT_REFRACTION_STRENGTH = 120f
        const val DEFAULT_BOTTOM_BAR_ICON_ONLY = true
        const val MIN_BAR_HEIGHT = 50f
        const val MAX_BAR_HEIGHT = 64f
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
                blurRadius = bundle.getFloat(ConfigContract.KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS),
                cornerRadiusPercent = bundle.getFloat(ConfigContract.KEY_CORNER_RADIUS_PERCENT, 50f),
                cornerSmoothing = bundle.getFloat(ConfigContract.KEY_CORNER_SMOOTHING, -1f),
                barHeight = bundle.getFloat(ConfigContract.KEY_BAR_HEIGHT, DEFAULT_BAR_HEIGHT),
                lightAlpha = bundle.getFloat(ConfigContract.KEY_LIGHT_ALPHA, DEFAULT_BAR_ALPHA),
                darkAlpha = bundle.getFloat(ConfigContract.KEY_DARK_ALPHA, DEFAULT_BAR_ALPHA),
                darkBarHighlightStrength = bundle.getFloat(ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, DEFAULT_HIGHLIGHT_STRENGTH_PERCENT / 100f),
                highlightTransparency = bundle.getFloat(ConfigContract.KEY_HIGHLIGHT_TRANSPARENCY, DEFAULT_HIGHLIGHT_TRANSPARENCY_PERCENT / 100f),
                iconScale = bundle.getFloat(ConfigContract.KEY_ICON_SCALE, 1f),
                textSize = bundle.getFloat(ConfigContract.KEY_TEXT_SIZE, 11f),
                iconOnly = bundle.getBoolean(ConfigContract.KEY_ICON_ONLY, false),
                hiddenMask = bundle.getInt(ConfigContract.KEY_HIDDEN_MASK, 0),
                detachedPublish = bundle.getBoolean(ConfigContract.KEY_DETACHED_PUBLISH, false),
                meituanDetachedXiaotuan = bundle.getBoolean(ConfigContract.KEY_MEITUAN_DETACHED_XIAOTUAN, true),
                bilibiliSolidTopGradient = bundle.getBoolean(ConfigContract.KEY_BILIBILI_SOLID_TOP_GRADIENT, true),
                bilibiliBottomColorGradient = bundle.getBoolean(ConfigContract.KEY_BILIBILI_BOTTOM_COLOR_GRADIENT, true),
                bilibiliTopBar = bundle.getBoolean(ConfigContract.KEY_BILIBILI_TOP_BAR, true),
                bilibiliEdgeToEdgeFeed = bundle.getBoolean(ConfigContract.KEY_BILIBILI_EDGE_TO_EDGE_FEED, false),
                bilibiliInDetachedSearch = bundle.getBoolean(ConfigContract.KEY_BILIBILI_IN_DETACHED_SEARCH, false),
                bilibiliDetachedSearch = bundle.getBoolean(ConfigContract.KEY_BILIBILI_DETACHED_SEARCH, false),
                bilibiliInVideoAmbient = bundle.getBoolean(ConfigContract.KEY_BILIBILI_IN_VIDEO_AMBIENT, false),
                bilibiliVideoAmbient = bundle.getBoolean(ConfigContract.KEY_BILIBILI_VIDEO_AMBIENT, false),
                douyinTopBar = bundle.getBoolean(ConfigContract.KEY_DOUYIN_TOP_BAR, true),
                tiktokTopBar = bundle.getBoolean(ConfigContract.KEY_TIKTOK_TOP_BAR, true),
                xhsTopBar = bundle.getBoolean(ConfigContract.KEY_XHS_TOP_BAR, true),
                xhsEdgeToEdgeFeed = bundle.getBoolean(ConfigContract.KEY_XHS_EDGE_TO_EDGE_FEED, false),
                weiboHomeTopBar = bundle.getBoolean(ConfigContract.KEY_WEIBO_HOME_TOP_BAR, true),
                managerEnglishNavigationLabels = bundle.getBoolean(ConfigContract.KEY_MANAGER_ENGLISH_LABELS, false),
                tiktokPinnedTabs = bundle.getString(ConfigContract.KEY_TIKTOK_PINNED_TABS,
                    io.github.offlineglass.config.TikTokTopChannels.DEFAULT_PINS).orEmpty(),
                douyinPinnedTabs = bundle.getString(ConfigContract.KEY_DOUYIN_PINNED_TABS, DouyinTopChannels.DEFAULT_PINS).orEmpty(),
                tiktokTallVideoFill = bundle.getBoolean(ConfigContract.KEY_TIKTOK_TALL_VIDEO_FILL, false),
                showPostButton = bundle.getBoolean(ConfigContract.KEY_SHOW_POST, true),
                customAccentEnabled = bundle.getBoolean(ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, false),
                customAccentColor = bundle.getInt(ConfigContract.KEY_CUSTOM_ACCENT_COLOR, DEFAULT_ACCENT),
                showChannel = bundle.getBoolean(ConfigContract.KEY_SHOW_CHANNEL, false),
                backdropCapture = bundle.getBoolean(ConfigContract.KEY_BACKDROP_CAPTURE, true),
                nativeBlur = bundle.getBoolean(ConfigContract.KEY_NATIVE_BLUR, false),
                selectedAccent = bundle.getBoolean(ConfigContract.KEY_SELECTED_ACCENT, false),
                spotifyBarOpacity = bundle.getFloat(ConfigContract.KEY_SPOTIFY_BAR_OPACITY, 1f),
                shortVideoBarOpacity = bundle.getFloat(ConfigContract.KEY_SHORT_VIDEO_BAR_OPACITY, 1f),
                spotifyProgressFill = bundle.getBoolean(ConfigContract.KEY_SPOTIFY_PROGRESS_FILL, true),
                wechatFoldPlanB = bundle.getBoolean(ConfigContract.KEY_WECHAT_FOLD_PLAN_B, false),
                wechatTopBarReconstruction = bundle.getBoolean(ConfigContract.KEY_WECHAT_TOP_BAR_RECONSTRUCTION, true),
                wechatTopBlurGradient = bundle.getBoolean(ConfigContract.KEY_WECHAT_TOP_BLUR_GRADIENT, false),
                xAutoHideBar = bundle.getBoolean(ConfigContract.KEY_X_AUTO_HIDE_BAR, false),
                miHealthSimplifyMine = bundle.getBoolean(ConfigContract.KEY_MI_HEALTH_SIMPLIFY_MINE, true),
                miHealthIosIcons = bundle.getBoolean(ConfigContract.KEY_MI_HEALTH_IOS_ICONS, true),
                spotifyThreeTabFullWidth = bundle.getBoolean(ConfigContract.KEY_SPOTIFY_THREE_TAB_FULL_WIDTH, true),
                spotifyPremiumAvailable = bundle.getBoolean(ConfigContract.KEY_SPOTIFY_PREMIUM_AVAILABLE, true),
                spotifyCreateCommand = bundle.getBoolean(ConfigContract.KEY_SPOTIFY_CREATE_COMMAND, false),
                liquidGlassEnabled = bundle.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true),
                bottomGradientBlurEnabled = false,
                solidBarEnabled = bundle.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false),
                outlineEnabled = bundle.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true),
                postureEffectsEnabled = bundle.getBoolean(ConfigContract.KEY_POSTURE_EFFECTS, false),
                indicatorInteractionLightEnabled = bundle.getBoolean(ConfigContract.KEY_INDICATOR_INTERACTION_LIGHT, true),
                simplifyTabSwitch = bundle.getBoolean(ConfigContract.KEY_SIMPLIFY_TAB_SWITCH, DEFAULT_SIMPLIFY_TAB_SWITCH),
                liquidViscosity = bundle.getFloat(ConfigContract.KEY_LIQUID_VISCOSITY, 25f),
                refractionStrength = bundle.getFloat(ConfigContract.KEY_REFRACTION_STRENGTH, DEFAULT_REFRACTION_STRENGTH),
                flowHorizontalStrength = bundle.getFloat(ConfigContract.KEY_FLOW_HORIZONTAL, 100f),
                flowVerticalStrength = bundle.getFloat(ConfigContract.KEY_FLOW_VERTICAL, 100f),
                classicNavigation = bundle.getBoolean(ConfigContract.KEY_CLASSIC_NAVIGATION, false),
                bottomBarIconOnly = bundle.getBoolean(ConfigContract.KEY_BOTTOM_BAR_ICON_ONLY, DEFAULT_BOTTOM_BAR_ICON_ONLY),
                systemDark = bundle.getBoolean(ConfigContract.KEY_SYSTEM_DARK, false),
            ).normalized(bundle.getString(ConfigContract.KEY_PACKAGE).orEmpty())
        }
    }
}

