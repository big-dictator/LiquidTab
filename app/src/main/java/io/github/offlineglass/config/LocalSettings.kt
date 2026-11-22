package io.github.offlineglass.config

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import io.github.offlineglass.targets.AppCatalog

enum class UiMode(val value: String) {
    MIUIX("miuix"),
    MATERIAL("material");

    companion object {
        fun fromValue(value: String?): UiMode = if (value == MATERIAL.value) MATERIAL else MIUIX
    }
}

enum class ColorMode(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String?): ColorMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

data class ManagerSettings(
    val uiMode: UiMode,
    val colorMode: ColorMode,
    val dynamicColor: Boolean,
    val liquidGlassEnabled: Boolean,
    val bottomGradientBlurEnabled: Boolean,
    val solidBarEnabled: Boolean,
    val outlineEnabled: Boolean,
    val glass: GlassConfig,
    val appEnabled: Map<String, Boolean>,
    val installedPackages: Set<String>,
    val appConfigs: Map<String, GlassConfig>,
    val appOverrides: Set<String>,
    val miMarketTabEnabled: Map<String, Boolean>,
    val lastActivePackage: String?,
    val lastActiveTime: Long,
    val appEntryLabels: Map<String, List<String>> = emptyMap(),
    val language: ManagerLanguage = ManagerLanguage.CHINESE,
    val douyinTopChannels: List<DouyinTopChannel> = emptyList(),
    val tiktokTopChannels: List<io.github.offlineglass.config.TikTokTopChannel> = emptyList(),
) {
    /** Manager controls follow the liquid-glass switch, never a second saved flag. */
    val iosStyle: Boolean get() = liquidGlassEnabled
    val frostedGlassEnabled: Boolean
        get() = !solidBarEnabled && !liquidGlassEnabled && glass.blurRadius > 0f
}

object LocalSettings {
    private const val PREFS = "offline_glass"
    private const val GLOBAL = "glass_"
    private const val PREVIOUS_LIQUID_GLASS = "previous_liquid_glass_enabled"
    private const val PREVIOUS_BOTTOM_GRADIENT = "previous_bottom_gradient_blur_enabled"
    private const val MI_MARKET_TAB_PREFIX = "mi_market_tab_"
    private val MI_MARKET_TAB_KEYS = listOf("pref_key_short_play", "pref_key_mini_game")

    private fun prefs(context: Context): SharedPreferences {
        // directBootAware lets the provider start while the user is still
        // locked, in which case its context points at device-protected
        // storage (DE) — a separate offline_glass.xml from the one the
        // manager UI writes (CE). Once unlocked, always resolve to the
        // credential-protected context so provider reads and UI writes hit
        // the same file; before unlock, CE is unreadable so fall back to DE.
        val ctx = if (!context.isDeviceProtectedStorage) {
            context
        } else {
            val unlocked = runCatching {
                context.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked
            }.getOrNull()
            if (unlocked == true) {
                runCatching {
                    context.createPackageContext(context.packageName, 0)
                }.getOrDefault(context)
            } else {
                context
            }
        }
        // Modern service exports an atomic mirror; never use deprecated world-readable files.
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        SharedPreferenceMigration.importLatest(ctx, prefs, PREFS)
        // Cold-start migration: copy existing settings from device-protected
        // storage (where earlier versions wrote) to the default credential-
        // protected storage that XSharedPreferences reads from, so the hook
        // code in target processes always sees the latest values.
        if (!ctx.isDeviceProtectedStorage && !prefs.contains("_migrated_from_de")) {
            val de = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (de.all.isNotEmpty()) {
                prefs.edit().apply {
                    de.all.forEach { (k, v) ->
                        // Existing CE values are the user's current settings,
                        // not a destination to overwrite with an old DE copy.
                        if (prefs.contains(k)) return@forEach
                        when (v) {
                            is String -> putString(k, v)
                            is Boolean -> putBoolean(k, v)
                            is Int -> putInt(k, v)
                            is Long -> putLong(k, v)
                            is Float -> putFloat(k, v)
                            is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, v as Set<String>)
                        }
                    }
                    putBoolean("_migrated_from_de", true)
                    apply()
                }
            } else {
                prefs.edit().putBoolean("_migrated_from_de", true).apply()
            }
        }
        // Undo the temporary blur-only migration once for affected installs.
        // New installs use the optical default; later manual choices still win.
        if (prefs.getBoolean("blur_only_debug_defaults_20261001", false) &&
            !prefs.getBoolean("liquid_glass_defaults_restored_20261001", false)) {
            prefs.edit()
                .putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
                .putBoolean("liquid_glass_defaults_restored_20261001", true)
                .commit()
        }
        return prefs
    }

    internal fun exportPreferences(context: Context): SharedPreferences = prefs(context)

    fun snapshot(context: Context, knownInstalledPackages: Set<String>? = null): ManagerSettings {
        val p = prefs(context)
        val liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
        val bottomGradientBlurEnabled = false
        val solidBarEnabled = p.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false)
        val outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
        val rawGlobal = readConfig(p, GLOBAL, GlassConfig())
        val global = rawGlobal.normalized().copy(
            classicNavigation = rawGlobal.classicNavigation,
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            solidBarEnabled = solidBarEnabled,
            outlineEnabled = outlineEnabled,
        )
        val apps = AppCatalog.targets.associate { target ->
            target.packageName to p.getBoolean("app_${target.packageName}", true)
        }
        val installedPackages = knownInstalledPackages ?: AppCatalog.targets.mapNotNullTo(linkedSetOf()) { target ->
            target.packageName.takeIf {
                runCatching { context.packageManager.getApplicationInfo(it, 0) }.isSuccess
            }
        }
        val overrides = AppCatalog.targets.mapNotNullTo(linkedSetOf()) { target ->
            target.packageName.takeIf { p.getBoolean(overrideKey(it), false) }
        }
        val configs = AppCatalog.targets.associate { target ->
            val enabled = apps[target.packageName] != false
            val defaults = defaultsFor(target.packageName, global)
            val value = if (target.packageName in overrides) {
                readConfig(p, appPrefix(target.packageName), defaults)
            } else {
                defaults
            }
            target.packageName to value.copy(
                enabled = enabled,
                blurRadius = global.blurRadius,
                cornerRadiusPercent = 50f,
                cornerSmoothing = global.cornerSmoothing,
                barHeight = global.barHeight,
                lightAlpha = global.lightAlpha,
                darkAlpha = global.darkAlpha,
                darkBarHighlightStrength = global.darkBarHighlightStrength,
                highlightTransparency = global.highlightTransparency,
                bottomGradientBlurEnabled = false,
            ).normalized(target.packageName)
        }
        return ManagerSettings(
            language = ManagerLanguage.fromValue(p.getString("manager_language", "zh")),
            uiMode = UiMode.fromValue(p.getString("ui_mode", UiMode.MIUIX.value)),
            colorMode = ColorMode.fromValue(p.getString("color_mode", ColorMode.SYSTEM.value)),
            dynamicColor = p.getBoolean("dynamic_color", false),
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            solidBarEnabled = solidBarEnabled,
            outlineEnabled = outlineEnabled,
            glass = global,
            appEnabled = apps,
            installedPackages = installedPackages,
            appConfigs = configs,
            appOverrides = overrides,
            miMarketTabEnabled = MI_MARKET_TAB_KEYS.associateWith { key ->
                p.getBoolean(miMarketTabKey(key), true)
            },
            lastActivePackage = p.getString(ConfigContract.KEY_LAST_ACTIVE_PACKAGE, null),
            lastActiveTime = p.getLong(ConfigContract.KEY_LAST_ACTIVE_TIME, 0L),
            appEntryLabels = AppCatalog.targets.associate { target ->
                target.packageName to runCatching {
                    val array = org.json.JSONArray(p.getString("navigation_labels_${target.packageName}", "[]"))
                    List(array.length()) { array.getString(it) }
                }.getOrDefault(emptyList())
            },
            douyinTopChannels = DouyinTopChannels.decode(p.getString(ConfigContract.KEY_DOUYIN_TOP_TABS, "[]").orEmpty()),
            tiktokTopChannels = io.github.offlineglass.config.TikTokTopChannels.decode(p.getString(ConfigContract.KEY_TIKTOK_TOP_CHANNELS, "[]").orEmpty()),
        )
    }

    fun configFor(context: Context, packageName: String): GlassConfig {
        val p = prefs(context)
        val canonicalPackage = AppCatalog.forPackage(packageName)?.packageName ?: packageName
        val liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
        val bottomGradientBlurEnabled = false
        val solidBarEnabled = p.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false)
        val outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
        val global = readConfig(p, GLOBAL, GlassConfig()).copy(
            classicNavigation = readConfig(p, GLOBAL, GlassConfig()).classicNavigation,
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            solidBarEnabled = solidBarEnabled,
            outlineEnabled = outlineEnabled,
        )
        val defaults = defaultsFor(packageName, global)
        val config = if (p.getBoolean(overrideKey(canonicalPackage), false)) {
            readConfig(p, appPrefix(canonicalPackage), defaults)
        } else {
            defaults
        }
        return config.copy(
            enabled = p.getBoolean("app_$canonicalPackage", true),
            blurRadius = global.blurRadius,
            cornerRadiusPercent = 50f,
            cornerSmoothing = global.cornerSmoothing,
            barHeight = global.barHeight,
            lightAlpha = global.lightAlpha,
            darkAlpha = global.darkAlpha,
            darkBarHighlightStrength = global.darkBarHighlightStrength,
            highlightTransparency = global.highlightTransparency,
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = false,
            solidBarEnabled = solidBarEnabled,
            outlineEnabled = outlineEnabled,
        ).normalized(canonicalPackage)
    }

    fun setUiMode(context: Context, value: UiMode) = edit(context) { putString("ui_mode", value.value) }
    @Synchronized
    fun setLanguage(context: Context, value: ManagerLanguage) {
        // Publish the selected module language as part of the normal versioned
        // config so adapter-painted labels update in already-running targets.
        edit(context) {
            putString("manager_language", value.value)
            putBoolean(GLOBAL + ConfigContract.KEY_MANAGER_ENGLISH_LABELS, value == ManagerLanguage.ENGLISH)
        }
    }
    fun setColorMode(context: Context, value: ColorMode) = edit(context) { putString("color_mode", value.value) }
    fun setDynamicColor(context: Context, value: Boolean) = edit(context) { putBoolean("dynamic_color", value) }
    fun setLiquidGlassEnabled(context: Context, value: Boolean) =
        edit(context) {
            if (value) putBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false)
            putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, value)
        }

    fun setFrostedGlassEnabled(context: Context, value: Boolean) {
        val p = prefs(context)
        edit(context) {
            putBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false)
            putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, !value)
            if (value && p.getFloat(GLOBAL + ConfigContract.KEY_BLUR_RADIUS, GlassConfig().blurRadius) == 0f) {
                putFloat(GLOBAL + ConfigContract.KEY_BLUR_RADIUS, 4f)
            }
        }
    }

    /** Publishes initial settings without overwriting stored preferences on upgrade. */
    fun applyFormalDefaultsOnce(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        var changed = false
        if (!p.getBoolean("douyin_apk_top_defaults_20261024", false)) {
            edit.putBoolean("douyin_apk_top_defaults_20261024", true)
            val pins = appPrefix("com.ss.android.ugc.aweme") + ConfigContract.KEY_DOUYIN_PINNED_TABS
            if (!p.contains(pins)) edit.putString(pins, DouyinTopChannels.DEFAULT_PINS)
            changed = true
        }
        // A first launch must export a versioned snapshot before any manual toggle.
        if (changed || !p.contains("revision")) {
            edit.putLong("revision", maxOf(System.currentTimeMillis(), p.getLong("revision", 0L) + 1L))
            check(edit.commit()) { "Initial settings export failed" }
        }
        synchronized(this) { ModernSettingsBridge.publish(p) }
    }

    fun setSolidBarEnabled(context: Context, value: Boolean) {
        val p = prefs(context)
        val previousLiquid = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
        val previousGradient = false
        edit(context) {
            putBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, value)
            if (value) {
                putBoolean(PREVIOUS_LIQUID_GLASS, previousLiquid)
                putBoolean(PREVIOUS_BOTTOM_GRADIENT, previousGradient)
                putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, false)
                putBoolean(ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED, false)
            } else {
                putBoolean(
                    ConfigContract.KEY_LIQUID_GLASS_ENABLED,
                    p.getBoolean(PREVIOUS_LIQUID_GLASS, true),
                )
                putBoolean(
                    ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED,
                    false,
                )
            }
        }
    }
    fun setOutlineEnabled(context: Context, value: Boolean) =
        edit(context) { putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, value) }
    fun setAppEnabled(context: Context, packageName: String, value: Boolean) =
        edit(context) { putBoolean("app_$packageName", value) }

    fun miMarketTabEnabled(context: Context, key: String): Boolean =
        prefs(context).getBoolean(miMarketTabKey(key), true)

    fun setMiMarketTabEnabled(context: Context, key: String, value: Boolean) {
        if (key !in MI_MARKET_TAB_KEYS) return
        edit(context) { putBoolean(miMarketTabKey(key), value) }
    }

    fun setGlassConfig(context: Context, value: GlassConfig) {
        android.util.Log.i(
            "LiquidTabUI",
            "setGlassConfig pkg=${context.packageName} blur=${value.blurRadius}",
        )
        edit(context) {
            writeConfig(GLOBAL, value.normalized())
        }
    }

    fun setAppConfig(context: Context, packageName: String, value: GlassConfig, spotifyNativeOrigin: Boolean = false) = edit(context) {
        if (packageName == "com.spotify.music") {
            val old = configFor(context, packageName)
            putBoolean(appPrefix(packageName) + ConfigContract.KEY_SPOTIFY_CREATE_COMMAND,
                !spotifyNativeOrigin && (old.spotifyCreateCommand || ((old.hiddenMask xor value.hiddenMask) and 16 != 0)))
        }
        putBoolean(overrideKey(packageName), true)
        writeConfig(appPrefix(packageName), value.normalized(packageName))
    }

    fun clearAppConfig(context: Context, packageName: String) = edit(context) {
        putBoolean(overrideKey(packageName), false)
    }

    /** Publishes the current complete configuration as one apply revision. */
    fun applyChanges(context: Context) = edit(context) { }

    fun reportActive(context: Context, packageName: String) = edit(context) {
        putString(ConfigContract.KEY_LAST_ACTIVE_PACKAGE, packageName)
        putLong(ConfigContract.KEY_LAST_ACTIVE_TIME, System.currentTimeMillis())
    }

    private fun readConfig(p: SharedPreferences, prefix: String, defaults: GlassConfig): GlassConfig = GlassConfig(
        postureEffectsEnabled = p.getBoolean(GLOBAL + ConfigContract.KEY_POSTURE_EFFECTS, false),
        indicatorInteractionLightEnabled = p.getBoolean(GLOBAL + ConfigContract.KEY_INDICATOR_INTERACTION_LIGHT, true),
        simplifyTabSwitch = p.getBoolean(GLOBAL + ConfigContract.KEY_SIMPLIFY_TAB_SWITCH, GlassConfig.DEFAULT_SIMPLIFY_TAB_SWITCH),
        liquidViscosity = p.getFloat(GLOBAL + ConfigContract.KEY_LIQUID_VISCOSITY, 25f),
        refractionStrength = p.getFloat(GLOBAL + ConfigContract.KEY_REFRACTION_STRENGTH, GlassConfig.DEFAULT_REFRACTION_STRENGTH),
        flowHorizontalStrength = p.getFloat(GLOBAL + ConfigContract.KEY_FLOW_HORIZONTAL, 100f),
        flowVerticalStrength = p.getFloat(GLOBAL + ConfigContract.KEY_FLOW_VERTICAL, 100f),
        classicNavigation = if (prefix == GLOBAL) p.getBoolean(GLOBAL + ConfigContract.KEY_CLASSIC_NAVIGATION, false) else defaults.classicNavigation,
        bottomBarIconOnly = p.getBoolean(GLOBAL + ConfigContract.KEY_BOTTOM_BAR_ICON_ONLY, GlassConfig.DEFAULT_BOTTOM_BAR_ICON_ONLY),
        enabled = defaults.enabled,
        themeMode = p.getInt(prefix + ConfigContract.KEY_THEME_MODE, defaults.themeMode),
        tabWidth = p.getFloat(prefix + ConfigContract.KEY_TAB_WIDTH, defaults.tabWidth),
        bottomPadding = p.getFloat(prefix + ConfigContract.KEY_BOTTOM_PADDING, defaults.bottomPadding),
        blurRadius = p.getFloat(prefix + ConfigContract.KEY_BLUR_RADIUS, defaults.blurRadius),
        // configFor/snapshot force legacy blurRadius to the global value. Inherit that
        // effective value until a dedicated SystemUI value has actually been saved.
        cornerRadiusPercent = p.getFloat(prefix + ConfigContract.KEY_CORNER_RADIUS_PERCENT, defaults.cornerRadiusPercent),
        cornerSmoothing = p.getFloat(prefix + ConfigContract.KEY_CORNER_SMOOTHING, defaults.cornerSmoothing),
        barHeight = p.getFloat(prefix + ConfigContract.KEY_BAR_HEIGHT, defaults.barHeight),
        lightAlpha = p.getFloat(prefix + ConfigContract.KEY_LIGHT_ALPHA, defaults.lightAlpha),
        darkAlpha = p.getFloat(prefix + ConfigContract.KEY_DARK_ALPHA, defaults.darkAlpha),
        darkBarHighlightStrength = p.getFloat(prefix + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, defaults.darkBarHighlightStrength),
        highlightTransparency = p.getFloat(prefix + ConfigContract.KEY_HIGHLIGHT_TRANSPARENCY, defaults.highlightTransparency),
        iconScale = p.getFloat(prefix + ConfigContract.KEY_ICON_SCALE, defaults.iconScale),
        textSize = p.getFloat(prefix + ConfigContract.KEY_TEXT_SIZE, defaults.textSize),
        iconOnly = p.getBoolean(prefix + ConfigContract.KEY_ICON_ONLY, defaults.iconOnly),
        hiddenMask = p.getInt(prefix + ConfigContract.KEY_HIDDEN_MASK, defaults.hiddenMask),
        detachedPublish = p.getBoolean(prefix + ConfigContract.KEY_DETACHED_PUBLISH, defaults.detachedPublish),
        meituanDetachedXiaotuan = p.getBoolean(prefix + ConfigContract.KEY_MEITUAN_DETACHED_XIAOTUAN, defaults.meituanDetachedXiaotuan),
        bilibiliSolidTopGradient = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_SOLID_TOP_GRADIENT, defaults.bilibiliSolidTopGradient),
        bilibiliBottomColorGradient = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_BOTTOM_COLOR_GRADIENT, defaults.bilibiliBottomColorGradient),
        bilibiliTopBar = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_TOP_BAR, defaults.bilibiliTopBar),
        bilibiliEdgeToEdgeFeed = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_EDGE_TO_EDGE_FEED, defaults.bilibiliEdgeToEdgeFeed),
        bilibiliInDetachedSearch = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_IN_DETACHED_SEARCH, defaults.bilibiliInDetachedSearch),
        bilibiliDetachedSearch = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_DETACHED_SEARCH, defaults.bilibiliDetachedSearch),
        bilibiliInVideoAmbient = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_IN_VIDEO_AMBIENT, defaults.bilibiliInVideoAmbient),
        bilibiliVideoAmbient = p.getBoolean(prefix + ConfigContract.KEY_BILIBILI_VIDEO_AMBIENT, defaults.bilibiliVideoAmbient),
        douyinTopBar = p.getBoolean(prefix + ConfigContract.KEY_DOUYIN_TOP_BAR, defaults.douyinTopBar),
        tiktokTopBar = p.getBoolean(prefix + ConfigContract.KEY_TIKTOK_TOP_BAR, defaults.tiktokTopBar),
        xhsTopBar = p.getBoolean(prefix + ConfigContract.KEY_XHS_TOP_BAR, defaults.xhsTopBar),
        xhsEdgeToEdgeFeed = p.getBoolean(prefix + ConfigContract.KEY_XHS_EDGE_TO_EDGE_FEED, defaults.xhsEdgeToEdgeFeed),
        weiboHomeTopBar = p.getBoolean(prefix + ConfigContract.KEY_WEIBO_HOME_TOP_BAR, defaults.weiboHomeTopBar),
        managerEnglishNavigationLabels = if (prefix == GLOBAL)
            ManagerLanguage.fromValue(p.getString("manager_language", null)) == ManagerLanguage.ENGLISH
            else defaults.managerEnglishNavigationLabels,
        tiktokPinnedTabs = p.getString(prefix + ConfigContract.KEY_TIKTOK_PINNED_TABS, defaults.tiktokPinnedTabs).orEmpty(),
        douyinPinnedTabs = p.getString(prefix + ConfigContract.KEY_DOUYIN_PINNED_TABS, defaults.douyinPinnedTabs).orEmpty(),
        tiktokTallVideoFill = p.getBoolean(prefix + ConfigContract.KEY_TIKTOK_TALL_VIDEO_FILL, defaults.tiktokTallVideoFill),
        showPostButton = p.getBoolean(prefix + ConfigContract.KEY_SHOW_POST, defaults.showPostButton),
        customAccentEnabled = p.getBoolean(prefix + ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, defaults.customAccentEnabled),
        customAccentColor = p.getInt(prefix + ConfigContract.KEY_CUSTOM_ACCENT_COLOR, defaults.customAccentColor),
        showChannel = p.getBoolean(prefix + ConfigContract.KEY_SHOW_CHANNEL, defaults.showChannel),
        backdropCapture = p.getBoolean(prefix + ConfigContract.KEY_BACKDROP_CAPTURE, defaults.backdropCapture),
        nativeBlur = p.getBoolean(prefix + ConfigContract.KEY_NATIVE_BLUR, defaults.nativeBlur),
        selectedAccent = p.getBoolean(prefix + ConfigContract.KEY_SELECTED_ACCENT, defaults.selectedAccent),
        spotifyBarOpacity = p.getFloat(prefix + ConfigContract.KEY_SPOTIFY_BAR_OPACITY, defaults.spotifyBarOpacity),
        shortVideoBarOpacity = p.getFloat(prefix + ConfigContract.KEY_SHORT_VIDEO_BAR_OPACITY, defaults.shortVideoBarOpacity),
        spotifyProgressFill = p.getBoolean(prefix + ConfigContract.KEY_SPOTIFY_PROGRESS_FILL, defaults.spotifyProgressFill),
        wechatFoldPlanB = p.getBoolean(prefix + ConfigContract.KEY_WECHAT_FOLD_PLAN_B, defaults.wechatFoldPlanB),
        wechatTopBarReconstruction = p.getBoolean(prefix + ConfigContract.KEY_WECHAT_TOP_BAR_RECONSTRUCTION, defaults.wechatTopBarReconstruction),
        wechatTopBlurGradient = p.getBoolean(prefix + ConfigContract.KEY_WECHAT_TOP_BLUR_GRADIENT, defaults.wechatTopBlurGradient),
        xAutoHideBar = p.getBoolean(prefix + ConfigContract.KEY_X_AUTO_HIDE_BAR, defaults.xAutoHideBar),
        miHealthSimplifyMine = p.getBoolean(prefix + ConfigContract.KEY_MI_HEALTH_SIMPLIFY_MINE, defaults.miHealthSimplifyMine),
        miHealthIosIcons = p.getBoolean(prefix + ConfigContract.KEY_MI_HEALTH_IOS_ICONS, defaults.miHealthIosIcons),
        spotifyThreeTabFullWidth = p.getBoolean(prefix + ConfigContract.KEY_SPOTIFY_THREE_TAB_FULL_WIDTH, defaults.spotifyThreeTabFullWidth),
        spotifyPremiumAvailable = p.getBoolean(prefix + ConfigContract.KEY_SPOTIFY_PREMIUM_AVAILABLE, defaults.spotifyPremiumAvailable),
        spotifyCreateCommand = p.getBoolean(prefix + ConfigContract.KEY_SPOTIFY_CREATE_COMMAND, false),
        liquidGlassEnabled = defaults.liquidGlassEnabled,
        bottomGradientBlurEnabled = defaults.bottomGradientBlurEnabled,
        solidBarEnabled = defaults.solidBarEnabled,
        outlineEnabled = defaults.outlineEnabled,
    ).normalized()

    private fun SharedPreferences.Editor.writeConfig(prefix: String, value: GlassConfig) {
        putBoolean(prefix + ConfigContract.KEY_POSTURE_EFFECTS, value.postureEffectsEnabled)
        putBoolean(prefix + ConfigContract.KEY_INDICATOR_INTERACTION_LIGHT, value.indicatorInteractionLightEnabled)
        putBoolean(prefix + ConfigContract.KEY_SIMPLIFY_TAB_SWITCH, value.simplifyTabSwitch)
        putFloat(prefix + ConfigContract.KEY_LIQUID_VISCOSITY, value.liquidViscosity)
        if (prefix == GLOBAL) putFloat(prefix + ConfigContract.KEY_REFRACTION_STRENGTH, value.refractionStrength)
        putFloat(prefix + ConfigContract.KEY_FLOW_HORIZONTAL, value.flowHorizontalStrength)
        putFloat(prefix + ConfigContract.KEY_FLOW_VERTICAL, value.flowVerticalStrength)
        if (prefix == GLOBAL) putBoolean(prefix + ConfigContract.KEY_CLASSIC_NAVIGATION, value.classicNavigation)
        if (prefix == GLOBAL) putBoolean(prefix + ConfigContract.KEY_BOTTOM_BAR_ICON_ONLY, value.bottomBarIconOnly)
        putInt(prefix + ConfigContract.KEY_THEME_MODE, value.themeMode)
        putFloat(prefix + ConfigContract.KEY_TAB_WIDTH, value.tabWidth)
        putFloat(prefix + ConfigContract.KEY_BOTTOM_PADDING, value.bottomPadding)
        putFloat(prefix + ConfigContract.KEY_BLUR_RADIUS, value.blurRadius)
        putFloat(prefix + ConfigContract.KEY_CORNER_RADIUS_PERCENT, value.cornerRadiusPercent)
        putFloat(prefix + ConfigContract.KEY_CORNER_SMOOTHING, value.cornerSmoothing)
        putFloat(prefix + ConfigContract.KEY_BAR_HEIGHT, value.barHeight)
        putFloat(prefix + ConfigContract.KEY_LIGHT_ALPHA, value.lightAlpha)
        putFloat(prefix + ConfigContract.KEY_DARK_ALPHA, value.darkAlpha)
        putFloat(prefix + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, value.darkBarHighlightStrength)
        putFloat(prefix + ConfigContract.KEY_HIGHLIGHT_TRANSPARENCY, value.highlightTransparency)
        putFloat(prefix + ConfigContract.KEY_ICON_SCALE, value.iconScale)
        putFloat(prefix + ConfigContract.KEY_TEXT_SIZE, value.textSize)
        putBoolean(prefix + ConfigContract.KEY_ICON_ONLY, value.iconOnly)
        putInt(prefix + ConfigContract.KEY_HIDDEN_MASK, value.hiddenMask)
        putBoolean(prefix + ConfigContract.KEY_DETACHED_PUBLISH, value.detachedPublish)
        putBoolean(prefix + ConfigContract.KEY_MEITUAN_DETACHED_XIAOTUAN, value.meituanDetachedXiaotuan)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_SOLID_TOP_GRADIENT, value.bilibiliSolidTopGradient)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_BOTTOM_COLOR_GRADIENT, value.bilibiliBottomColorGradient)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_TOP_BAR, value.bilibiliTopBar)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_EDGE_TO_EDGE_FEED, value.bilibiliEdgeToEdgeFeed)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_IN_DETACHED_SEARCH, value.bilibiliInDetachedSearch)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_DETACHED_SEARCH, value.bilibiliDetachedSearch)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_IN_VIDEO_AMBIENT, value.bilibiliInVideoAmbient)
        putBoolean(prefix + ConfigContract.KEY_BILIBILI_VIDEO_AMBIENT, value.bilibiliVideoAmbient)
        putBoolean(prefix + ConfigContract.KEY_DOUYIN_TOP_BAR, value.douyinTopBar)
        putBoolean(prefix + ConfigContract.KEY_TIKTOK_TOP_BAR, value.tiktokTopBar)
        putBoolean(prefix + ConfigContract.KEY_XHS_TOP_BAR, value.xhsTopBar)
        putBoolean(prefix + ConfigContract.KEY_XHS_EDGE_TO_EDGE_FEED, value.xhsEdgeToEdgeFeed)
        putBoolean(prefix + ConfigContract.KEY_WEIBO_HOME_TOP_BAR, value.weiboHomeTopBar)
        if (prefix == GLOBAL) putBoolean(prefix + ConfigContract.KEY_MANAGER_ENGLISH_LABELS, value.managerEnglishNavigationLabels)
        putString(prefix + ConfigContract.KEY_TIKTOK_PINNED_TABS, value.tiktokPinnedTabs)
        putString(prefix + ConfigContract.KEY_DOUYIN_PINNED_TABS, value.douyinPinnedTabs)
        putBoolean(prefix + ConfigContract.KEY_TIKTOK_TALL_VIDEO_FILL, value.tiktokTallVideoFill)
        putBoolean(prefix + ConfigContract.KEY_SHOW_POST, value.showPostButton)
        putBoolean(prefix + ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, value.customAccentEnabled)
        putInt(prefix + ConfigContract.KEY_CUSTOM_ACCENT_COLOR, value.customAccentColor)
        putBoolean(prefix + ConfigContract.KEY_SHOW_CHANNEL, value.showChannel)
        putBoolean(prefix + ConfigContract.KEY_BACKDROP_CAPTURE, value.backdropCapture)
        putBoolean(prefix + ConfigContract.KEY_NATIVE_BLUR, value.nativeBlur)
        putBoolean(prefix + ConfigContract.KEY_SELECTED_ACCENT, value.selectedAccent)
        putFloat(prefix + ConfigContract.KEY_SPOTIFY_BAR_OPACITY, value.spotifyBarOpacity)
        putFloat(prefix + ConfigContract.KEY_SHORT_VIDEO_BAR_OPACITY, value.shortVideoBarOpacity)
        putBoolean(prefix + ConfigContract.KEY_SPOTIFY_PROGRESS_FILL, value.spotifyProgressFill)
        putBoolean(prefix + ConfigContract.KEY_WECHAT_FOLD_PLAN_B, value.wechatFoldPlanB)
        putBoolean(prefix + ConfigContract.KEY_WECHAT_TOP_BAR_RECONSTRUCTION, value.wechatTopBarReconstruction)
        putBoolean(prefix + ConfigContract.KEY_WECHAT_TOP_BLUR_GRADIENT, value.wechatTopBlurGradient)
        putBoolean(prefix + ConfigContract.KEY_X_AUTO_HIDE_BAR, value.xAutoHideBar)
        putBoolean(prefix + ConfigContract.KEY_MI_HEALTH_SIMPLIFY_MINE, value.miHealthSimplifyMine)
        putBoolean(prefix + ConfigContract.KEY_MI_HEALTH_IOS_ICONS, value.miHealthIosIcons)
        putBoolean(prefix + ConfigContract.KEY_SPOTIFY_THREE_TAB_FULL_WIDTH, value.spotifyThreeTabFullWidth)
        putBoolean(prefix + ConfigContract.KEY_SPOTIFY_PREMIUM_AVAILABLE, value.spotifyPremiumAvailable)
    }

    private inline fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        synchronized(this) {
        val p = prefs(context)
        val now = maxOf(System.currentTimeMillis(), p.getLong("revision", 0L) + 1L)
        check(p.edit().apply(block).putLong("revision", now).commit()) { "Settings commit failed" }
        }
        enqueuePublication(context.applicationContext)
    }

    private val publicationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-SettingsPublish").apply { isDaemon = true }
    }
    private val publicationLock = Any()
    private var publicationDirty = false
    private var publicationRunning = false

    /** Keep commit/read-your-write on the caller; no IPC fan-out in an animation frame. */
    private fun enqueuePublication(context: Context) {
        synchronized(publicationLock) {
            publicationDirty = true
            if (publicationRunning) return
            publicationRunning = true
        }
        publicationExecutor.execute {
            while (true) {
                synchronized(publicationLock) {
                    if (!publicationDirty) { publicationRunning = false; return@execute }
                    publicationDirty = false
                }
                try {
                    publishCommittedSettings(context)
                } catch (error: Exception) {
                    android.util.Log.e("LiquidTabSettings", "Configuration publication failed", error)
                }
            }
        }
    }

    private fun publishCommittedSettings(context: Context) {
        ModernSettingsBridge.publish(prefs(context))
        context.contentResolver.notifyChange(ConfigContract.URI, null)
        // A provider notification does not wake an otherwise idle injected
        // View, so polling alone cannot make a released slider visible in all
        // running targets. Push one complete snapshot per target only after
        // the setting commit; slider drag frames remain local to the manager.
        AppCatalog.targets.forEach { publishConfig(context, it.packageName) }
    }

    /** Same versioned snapshot for provider, push and cold-start request/reply. */
    @Synchronized
    fun configSnapshot(context: Context, packageName: String): android.os.Bundle =
        configFor(context, packageName).copy(systemDark = SystemTheme.isDark(context))
            .toBundle(packageName).apply {
                putLong(ConfigContract.KEY_REVISION, prefs(context).getLong("revision", 0L))
            }

    fun publishConfig(context: Context, packageName: String) {
        val spec = AppCatalog.forPackage(packageName) ?: return
        (listOf(spec.packageName) + spec.aliasPackages).forEach { targetPackage ->
            context.sendBroadcast(Intent(ConfigContract.ACTION_CONFIG_RESPONSE)
                .setPackage(targetPackage)
                .putExtras(configSnapshot(context, targetPackage)))
        }
    }
    fun reportNavigationLabels(context: Context, packageName: String, labels: List<String>) {
        if (AppCatalog.forPackage(packageName) == null || labels.size !in 2..7) return
        val value = org.json.JSONArray(labels).toString()
        val p = prefs(context)
        val key = "navigation_labels_$packageName"
        if (p.getString(key, null) == value) return
        p.edit().putString(key, value).apply()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
    }

    fun reportDouyinTopChannels(context: Context, value: String) {
        val channels = DouyinTopChannels.decode(value)
        if (channels.isEmpty()) return
        val normalized = DouyinTopChannels.encode(channels)
        val p = prefs(context)
        if (p.getString(ConfigContract.KEY_DOUYIN_TOP_TABS, null) == normalized) return
        p.edit().putString(ConfigContract.KEY_DOUYIN_TOP_TABS, normalized).apply()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
    }

    fun reportTikTokTopChannels(context: Context, value: String) {
        val p = prefs(context)
        if (p.getString(ConfigContract.KEY_TIKTOK_TOP_CHANNELS, null) == value) return
        p.edit().putString(ConfigContract.KEY_TIKTOK_TOP_CHANNELS, value).apply()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
    }

    private fun isSystemDarkNow(): Boolean {
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val night = android.content.res.Configuration.UI_MODE_NIGHT_YES
        return android.content.res.Resources.getSystem().configuration.uiMode and mask == night
    }

    private fun appPrefix(packageName: String) = "appcfg_${packageName}_"
    private fun overrideKey(packageName: String) = "appcfg_${packageName}_override"
    private fun miMarketTabKey(key: String) = MI_MARKET_TAB_PREFIX + key

    private fun defaultsFor(packageName: String, global: GlassConfig): GlassConfig {
        val spec = AppCatalog.forPackage(packageName) ?: return global
        return global.copy(
            iconOnly = spec.defaultIconOnly || global.iconOnly,
            hiddenMask = global.hiddenMask or spec.defaultHiddenMask,
            customAccentColor = spec.defaultAccentColor,
            detachedPublish = spec.defaultDetachedPublish || global.detachedPublish,
        )
    }
}

