package io.github.offlineglass.hook

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XSharedPreferences
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry

object HookConfigReader {
    // Separate from the compositor worker: slow provider IPC must not hold up frames.
    private val refreshExecutor = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 10L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task ->
            Thread(task, "LiquidTab-Config").apply { isDaemon = true }
        },
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun readFreshAsync(context: Context, packageName: String, complete: (GlassConfig?) -> Unit) {
        val appContext = context.applicationContext ?: context
        refreshExecutor.execute {
            val value = runCatching { readFresh(appContext, packageName) }.getOrNull()
            mainHandler.post { complete(value) }
        }
    }

    private data class Cached(val packageName: String, val config: GlassConfig, val time: Long)
    @Volatile private var cached: Cached? = null
    @Volatile private var qqProcessConfig: GlassConfig? = null

    /**
     * When the module's SettingsProvider is not running (the usual case for a
     * hooked target app), every poll first pays a failing resolver IPC and
     * then falls back to a full XSharedPreferences disk read + XML parse on
     * the UI thread. GlassHostLayout polls once per second from onPreDraw,
     * so back off after a provider failure and serve the last fallback value
     * from memory until the backoff window expires.
     */
    @Volatile private var providerFailedAt = 0L
    @Volatile private var fallbackCache: Cached? = null

    /**
     * Process-load gate for the per-app master switch. This is intentionally
     * evaluated before any target-specific method hook is installed: a
     * disabled app must run with its completely native navigation and layout,
     * not merely with the GlassHost hidden after mutations have begun.
     */
    fun isAppEnabledAtProcessLoad(packageName: String): Boolean =
        readThroughLsposed(packageName).enabled

    fun read(context: Context, packageName: String): GlassConfig {
        TargetAdapterRegistry.forPackage(packageName)?.pushedConfig()?.let { return it }
        if (packageName == QQ_PACKAGE) {
            qqProcessConfig?.let { return it }
            return synchronized(this) {
                qqProcessConfig ?: readCurrent(context, packageName).also { qqProcessConfig = it }
            }
        }
        val now = System.currentTimeMillis()
        cached?.takeIf { it.packageName == packageName && now - it.time < 2_000L }?.let { return it.config }

        val value = readCurrent(context, packageName)
        cached = Cached(packageName, value, now)
        return value
    }

    /** Bypasses the short process-local cache for an already attached glass host. */
    fun readFresh(context: Context, packageName: String): GlassConfig {
        TargetAdapterRegistry.forPackage(packageName)?.pushedConfig()?.let { return it }
        if (packageName == QQ_PACKAGE) return read(context, packageName)
        val value = readCurrent(context, packageName)
        cached = Cached(packageName, value, System.currentTimeMillis())
        return value
    }

    fun acceptPushedConfig(packageName: String, config: GlassConfig) {
        if (AppCatalog.forPackage(packageName) == null) return
        val value = config.normalized()
        // Adapter-owned storage is optional, not a prerequisite for accepting
        // a committed module snapshot. Otherwise most adapters discarded the
        // push here and the next poll/rebind restored an older fallback value.
        TargetAdapterRegistry.forPackage(packageName)?.acceptPushedConfig(value)
        val snapshot = Cached(packageName, value, System.currentTimeMillis())
        cached = snapshot
        fallbackCache = snapshot
        providerFailedAt = 0L
    }

    /**
     * Reads current config — prefers the module's exported SettingsProvider
     * and falls back to XSharedPreferences.
     *
     * The `xposedsharedprefs` meta-data has been removed from the manifest
     * so the module's SharedPreferences writes to the standard CE path
     * (/data/user/0/io.github.offlineglass/shared_prefs/offline_glass.xml),
     * where the provider reads from and the UI writes to.  Both the provider
     * call and the XSharedPreferences fallback return the same value, but
     * the provider is tried first for speed (in-process IPC).
     */
    private fun readCurrent(context: Context, packageName: String): GlassConfig {
        val now = System.currentTimeMillis()
        if (AppCatalog.forPackage(packageName) != null &&
            now - providerFailedAt >= PROVIDER_FAILURE_BACKOFF_MS
        ) {
            val viaProvider = readThroughProvider(context, packageName)
            if (viaProvider != null) {
                providerFailedAt = 0L
                return viaProvider
            }
            providerFailedAt = now
            XposedBridge.log(
                "[OfflineGlass][CfgDiag] provider unavailable for $packageName, " +
                    "falling back to XSharedPreferences",
            )
        }
        fallbackCache?.takeIf {
            it.packageName == packageName && now - it.time < PROVIDER_FAILURE_BACKOFF_MS
        }?.let { return it.config }
        val value = readThroughLsposed(packageName)
        XposedBridge.log(
            "[OfflineGlass][CfgDiag] lsposed read for $packageName: blur=${value.blurRadius} " +
                "liquid=${value.liquidGlassEnabled} enabled=${value.enabled}",
        )
        fallbackCache = Cached(packageName, value, now)
        return value
    }

    private fun readThroughProvider(context: Context, packageName: String): GlassConfig? =
        runCatching {
            val bundle = context.contentResolver.call(
                ConfigContract.URI,
                ConfigContract.METHOD_GET,
                packageName,
                null,
            )
            if (bundle == null) {
                XposedBridge.log("[OfflineGlass][CfgDiag] provider call returned null bundle")
                return@runCatching null
            }
            if (bundle.getString(ConfigContract.KEY_PACKAGE) != packageName) {
                XposedBridge.log(
                    "[OfflineGlass][CfgDiag] provider package mismatch: " +
                        bundle.getString(ConfigContract.KEY_PACKAGE),
                )
                return@runCatching null
            }
            val parsed = GlassConfig.fromBundle(bundle)
            XposedBridge.log(
                "[OfflineGlass][CfgDiag] provider read for $packageName: " +
                    "blur=${parsed.blurRadius} enabled=${parsed.enabled}",
            )
            parsed
        }.onFailure {
            XposedBridge.log("[OfflineGlass][CfgDiag] provider call threw: $it")
        }.getOrNull()

    fun reportActive(context: Context, packageName: String) {
        // Deliberately no cross-package provider wake-up on HyperOS. Settings are
        // reloaded from XSharedPreferences whenever the target process restarts.
    }

    private fun readThroughLsposed(packageName: String): GlassConfig = runCatching {
        val p = XSharedPreferences(MODULE_PACKAGE, PREF_FILE)
        p.reload()
        val global = readConfig(p, GLOBAL, GlassConfig())
            .copy(
                liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true),
                bottomGradientBlurEnabled = false,
                solidBarEnabled = p.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false),
                outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true),
            )
        val spec = AppCatalog.forPackage(packageName)
        val canonicalPackage = spec?.packageName ?: packageName
        val defaults = global.copy(
            iconOnly = (spec?.defaultIconOnly == true) || global.iconOnly,
            customAccentColor = spec?.defaultAccentColor ?: global.customAccentColor,
        )
        val config = if (p.getBoolean("appcfg_${canonicalPackage}_override", false)) {
            readConfig(p, "appcfg_${canonicalPackage}_", defaults)
        } else {
            defaults
        }
        config.copy(
            enabled = p.getBoolean("app_$canonicalPackage", true),
            blurRadius = global.blurRadius,
            cornerRadiusPercent = 50f,
            cornerSmoothing = global.cornerSmoothing,
            barHeight = global.barHeight,
            lightAlpha = global.lightAlpha,
            darkAlpha = global.darkAlpha,
            darkBarHighlightStrength = global.darkBarHighlightStrength,
            bottomGradientBlurEnabled = false,
        ).normalized()
    }.getOrElse { GlassConfig() }

    private fun readConfig(p: XSharedPreferences, prefix: String, defaults: GlassConfig): GlassConfig = GlassConfig(
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

    private const val MODULE_PACKAGE = "io.github.offlineglass"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val PREF_FILE = "offline_glass"
    private const val GLOBAL = "glass_"
    private const val PROVIDER_FAILURE_BACKOFF_MS = 5_000L
}
