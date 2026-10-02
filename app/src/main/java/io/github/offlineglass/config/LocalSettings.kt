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
)

object LocalSettings {
    private const val PREFS = "offline_glass"
    private const val GLOBAL = "glass_"
    private const val PREVIOUS_LIQUID_GLASS = "previous_liquid_glass_enabled"
    private const val PREVIOUS_BOTTOM_GRADIENT = "previous_bottom_gradient_blur_enabled"
    private const val FORMAL_DEFAULTS_V1 = "formal_defaults_v1"
    private const val GLOBAL_DEFAULTS_V2 = "global_defaults_v2"
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
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Cold-start migration: copy existing settings from device-protected
        // storage (where earlier versions wrote) to the default credential-
        // protected storage that XSharedPreferences reads from, so the hook
        // code in target processes always sees the latest values.
        if (!prefs.contains("_migrated_from_de")) {
            val de = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (de.all.isNotEmpty()) {
                prefs.edit().apply {
                    de.all.forEach { (k, v) ->
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

    fun snapshot(context: Context): ManagerSettings {
        val p = prefs(context)
        val liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true)
        val bottomGradientBlurEnabled = false
        val solidBarEnabled = p.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false)
        val outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
        val global = readConfig(p, GLOBAL, GlassConfig()).normalized().copy(
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = bottomGradientBlurEnabled,
            solidBarEnabled = solidBarEnabled,
            outlineEnabled = outlineEnabled,
        )
        val apps = AppCatalog.targets.associate { target ->
            target.packageName to p.getBoolean("app_${target.packageName}", true)
        }
        val installedPackages = AppCatalog.targets.mapNotNullTo(linkedSetOf()) { target ->
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
                bottomGradientBlurEnabled = false,
            )
        }
        return ManagerSettings(
            uiMode = UiMode.fromValue(p.getString("ui_mode", UiMode.MIUIX.value)),
            colorMode = ColorMode.fromValue(p.getString("color_mode", ColorMode.SYSTEM.value)),
            dynamicColor = p.getBoolean("dynamic_color", true),
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
            liquidGlassEnabled = liquidGlassEnabled,
            bottomGradientBlurEnabled = false,
            solidBarEnabled = solidBarEnabled,
            outlineEnabled = outlineEnabled,
        ).normalized()
    }

    fun setUiMode(context: Context, value: UiMode) = edit(context) { putString("ui_mode", value.value) }
    fun setColorMode(context: Context, value: ColorMode) = edit(context) { putString("color_mode", value.value) }
    fun setDynamicColor(context: Context, value: Boolean) = edit(context) { putBoolean("dynamic_color", value) }
    fun setLiquidGlassEnabled(context: Context, value: Boolean) =
        edit(context) { putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, value) }

    /** Applies the public 1.0 defaults once, including when upgrading from a debug build. */
    fun applyFormalDefaultsOnce(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        var changed = false
        if (!p.getBoolean(FORMAL_DEFAULTS_V1, false)) {
            edit.putBoolean(FORMAL_DEFAULTS_V1, true)
                .putBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, false)
                .putBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false)
                .putBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true)
            changed = true
        }
        if (!p.getBoolean(GLOBAL_DEFAULTS_V2, false)) {
            edit.putBoolean(GLOBAL_DEFAULTS_V2, true)
                .putBoolean(ConfigContract.KEY_BOTTOM_GRADIENT_BLUR_ENABLED, false)
                .putFloat(GLOBAL + ConfigContract.KEY_BLUR_RADIUS, 2f)
                .putFloat(GLOBAL + ConfigContract.KEY_CORNER_RADIUS_PERCENT, 50f)
                .putFloat(GLOBAL + ConfigContract.KEY_CORNER_SMOOTHING, -1f)
                .putFloat(GLOBAL + ConfigContract.KEY_BAR_HEIGHT, 56f)
            changed = true
        }
        if (!p.getBoolean("manager_defaults_20261002", false)) {
            edit.putBoolean("manager_defaults_20261002", true)
                .putFloat(GLOBAL + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, 0.5f)
            changed = true
        }
        if (changed) edit.apply()
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

    fun setAppConfig(context: Context, packageName: String, value: GlassConfig) = edit(context) {
        putBoolean(overrideKey(packageName), true)
        writeConfig(appPrefix(packageName), value.normalized())
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
        classicNavigation = if (prefix == GLOBAL) p.getBoolean(GLOBAL + ConfigContract.KEY_CLASSIC_NAVIGATION, false) else defaults.classicNavigation,
        enabled = defaults.enabled,
        themeMode = p.getInt(prefix + ConfigContract.KEY_THEME_MODE, defaults.themeMode),
        tabWidth = p.getFloat(prefix + ConfigContract.KEY_TAB_WIDTH, defaults.tabWidth),
        bottomPadding = p.getFloat(prefix + ConfigContract.KEY_BOTTOM_PADDING, defaults.bottomPadding),
        blurRadius = p.getFloat(prefix + ConfigContract.KEY_BLUR_RADIUS, defaults.blurRadius),
        cornerRadiusPercent = p.getFloat(prefix + ConfigContract.KEY_CORNER_RADIUS_PERCENT, defaults.cornerRadiusPercent),
        cornerSmoothing = p.getFloat(prefix + ConfigContract.KEY_CORNER_SMOOTHING, defaults.cornerSmoothing),
        barHeight = p.getFloat(prefix + ConfigContract.KEY_BAR_HEIGHT, defaults.barHeight),
        lightAlpha = p.getFloat(prefix + ConfigContract.KEY_LIGHT_ALPHA, defaults.lightAlpha),
        darkAlpha = p.getFloat(prefix + ConfigContract.KEY_DARK_ALPHA, defaults.darkAlpha),
        darkBarHighlightStrength = p.getFloat(prefix + ConfigContract.KEY_DARK_BAR_HIGHLIGHT_STRENGTH, defaults.darkBarHighlightStrength),
        iconScale = p.getFloat(prefix + ConfigContract.KEY_ICON_SCALE, defaults.iconScale),
        textSize = p.getFloat(prefix + ConfigContract.KEY_TEXT_SIZE, defaults.textSize),
        iconOnly = p.getBoolean(prefix + ConfigContract.KEY_ICON_ONLY, defaults.iconOnly),
        hiddenMask = p.getInt(prefix + ConfigContract.KEY_HIDDEN_MASK, defaults.hiddenMask),
        showPostButton = p.getBoolean(prefix + ConfigContract.KEY_SHOW_POST, defaults.showPostButton),
        customAccentEnabled = p.getBoolean(prefix + ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, defaults.customAccentEnabled),
        customAccentColor = p.getInt(prefix + ConfigContract.KEY_CUSTOM_ACCENT_COLOR, defaults.customAccentColor),
        showChannel = p.getBoolean(prefix + ConfigContract.KEY_SHOW_CHANNEL, defaults.showChannel),
        backdropCapture = p.getBoolean(prefix + ConfigContract.KEY_BACKDROP_CAPTURE, defaults.backdropCapture),
        nativeBlur = p.getBoolean(prefix + ConfigContract.KEY_NATIVE_BLUR, defaults.nativeBlur),
        selectedAccent = p.getBoolean(prefix + ConfigContract.KEY_SELECTED_ACCENT, defaults.selectedAccent),
        liquidGlassEnabled = defaults.liquidGlassEnabled,
        bottomGradientBlurEnabled = defaults.bottomGradientBlurEnabled,
        solidBarEnabled = defaults.solidBarEnabled,
        outlineEnabled = defaults.outlineEnabled,
    ).normalized()

    private fun SharedPreferences.Editor.writeConfig(prefix: String, value: GlassConfig) {
        if (prefix == GLOBAL) putBoolean(prefix + ConfigContract.KEY_CLASSIC_NAVIGATION, value.classicNavigation)
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
        putFloat(prefix + ConfigContract.KEY_ICON_SCALE, value.iconScale)
        putFloat(prefix + ConfigContract.KEY_TEXT_SIZE, value.textSize)
        putBoolean(prefix + ConfigContract.KEY_ICON_ONLY, value.iconOnly)
        putInt(prefix + ConfigContract.KEY_HIDDEN_MASK, value.hiddenMask)
        putBoolean(prefix + ConfigContract.KEY_SHOW_POST, value.showPostButton)
        putBoolean(prefix + ConfigContract.KEY_CUSTOM_ACCENT_ENABLED, value.customAccentEnabled)
        putInt(prefix + ConfigContract.KEY_CUSTOM_ACCENT_COLOR, value.customAccentColor)
        putBoolean(prefix + ConfigContract.KEY_SHOW_CHANNEL, value.showChannel)
        putBoolean(prefix + ConfigContract.KEY_BACKDROP_CAPTURE, value.backdropCapture)
        putBoolean(prefix + ConfigContract.KEY_NATIVE_BLUR, value.nativeBlur)
        putBoolean(prefix + ConfigContract.KEY_SELECTED_ACCENT, value.selectedAccent)
    }

    private inline fun edit(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val now = System.currentTimeMillis()
        prefs(context).edit().apply(block).putLong("revision", now).commit()
        context.contentResolver.notifyChange(ConfigContract.URI, null)
        // A provider notification does not wake an otherwise idle injected
        // View, so polling alone cannot make a released slider visible in all
        // running targets. Push one complete snapshot per target only after
        // the setting commit; slider drag frames remain local to the manager.
        AppCatalog.targets.forEach { publishConfig(context, it.packageName) }
    }

    fun publishConfig(context: Context, packageName: String) {
        val spec = AppCatalog.forPackage(packageName) ?: return
        val targets = buildList {
            add(spec.packageName)
            addAll(spec.aliasPackages)
        }
        targets.forEach { targetPackage ->
            val config = configFor(context, targetPackage).copy(systemDark = isSystemDarkNow())
            context.sendBroadcast(
                Intent(ConfigContract.ACTION_CONFIG_RESPONSE)
                    .setPackage(targetPackage)
                    .putExtras(config.toBundle(targetPackage)),
            )
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
            customAccentColor = spec.defaultAccentColor,
        )
    }

}
