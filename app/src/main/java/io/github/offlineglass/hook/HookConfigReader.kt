package io.github.offlineglass.hook

import android.content.Context
import io.github.offlineglass.hook.compat.XposedBridge
import android.content.SharedPreferences
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.config.SystemNavigationMode
import io.github.offlineglass.targets.AppCatalog
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry

object HookConfigReader {
    private val committedConfigs = CommittedConfigStore<GlassConfig>()
    private var startupPreferenceStatus = "not-read"
    private var writtenStartupPreferenceStatus: String? = null

    private fun pushedConfig(packageName: String): GlassConfig? =
        TargetAdapterRegistry.forPackage(packageName)?.pushedConfig() ?: committedConfigs.get(packageName)
    // Separate from the compositor worker: slow provider IPC must not hold up frames.
    private val refreshExecutor = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 10L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { task ->
            Thread(task, "LiquidTab-Config").apply { isDaemon = true }
        },
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingReads = HashMap<String, MutableList<(GlassConfig?) -> Unit>>()

    fun readFreshAsync(context: Context, packageName: String, complete: (GlassConfig?) -> Unit) {
        synchronized(pendingReads) {
            pendingReads[packageName]?.let { it.add(complete); return }
            pendingReads[packageName] = mutableListOf(complete)
        }
        val appContext = context.applicationContext ?: context
        refreshExecutor.execute {
            val value = runCatching { readFresh(appContext, packageName) }.getOrNull()
            mainHandler.post {
                val callbacks = synchronized(pendingReads) {
                    pendingReads.remove(packageName)?.toList().orEmpty()
                }
                fun deliver(snapshot: GlassConfig?) {
                    callbacks.forEach { callback ->
                        runCatching { callback(snapshot) }.onFailure {
                            android.util.Log.w("LiquidTabConfig", "refresh callback failed", it)
                        }
                    }
                }
                deliver(pushedConfig(packageName) ?: value)
                requestConfigSync(appContext, packageName, ::deliver)
            }
        }
    }

    private data class Cached(val packageName: String, val config: GlassConfig, val time: Long)
    @Volatile private var cached: Cached? = null

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
        startupSnapshot(packageName)?.enabled == true

    /** Only real snapshots may decide cold-start hooks; never promote defaults. */
    fun startupSnapshot(packageName: String): GlassConfig? =
        if (committedConfigs.hasVerifiedRevision(packageName)) committedConfigs.get(packageName)?.also {
            io.github.offlineglass.rendering.GlassEdgeHighlight.transparency = it.highlightTransparency
        } else null

    fun readStartupConfig(context: Context, packageName: String): GlassConfig? {
        startupSnapshot(packageName)?.let { return it }
        val now = System.currentTimeMillis()
        if (now - providerFailedAt >= PROVIDER_FAILURE_BACKOFF_MS) {
            if (readThroughProvider(context, packageName) != null) return startupSnapshot(packageName)
            providerFailedAt = now
        }
        // The module now exports this exact preferences file through LSPosed.
        // All targets must be able to use it, not only adapters with a former opt-in.
        readThroughLsposed(packageName, commitStartup = true)
        if (writtenStartupPreferenceStatus != startupPreferenceStatus) {
            writtenStartupPreferenceStatus = startupPreferenceStatus
            val report = "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} pkg=$packageName $startupPreferenceStatus\n"
            android.util.Log.i("LiquidTabStartup", report.trim())
            runCatching { java.io.File(context.getExternalFilesDir(null), "startup_preferences_status.txt").writeText(report) }
            // Preserve the existing dedicated diagnostic used by the Douyin workspace.
            if (TargetAdapterRegistry.forPackage(packageName)?.acceptsVersionedStartupPreferences == true) {
                runCatching { java.io.File(context.getExternalFilesDir(null), "dy_startup_preferences_status.txt").writeText(report) }
            }
        }
        return startupSnapshot(packageName)
    }

    fun read(context: Context, packageName: String): GlassConfig {
        pushedConfig(packageName)?.let {
            requestConfigSync(context, packageName)
            return it
        }
        val now = System.currentTimeMillis()
        cached?.takeIf { it.packageName == packageName && now - it.time < 2_000L }?.let {
            return it.config
        }

        val value = readCurrent(context, packageName)
        io.github.offlineglass.rendering.GlassEdgeHighlight.transparency = value.highlightTransparency
        cached = Cached(packageName, value, now)
        return value
    }

    /** Bypasses the short process-local cache for an already attached glass host. */
    fun readFresh(context: Context, packageName: String): GlassConfig {
        requestConfigSync(context, packageName)
        val value = readCurrent(context, packageName)
        io.github.offlineglass.rendering.GlassEdgeHighlight.transparency = value.highlightTransparency
        cached = Cached(packageName, value, System.currentTimeMillis())
        return value
    }

    @Synchronized
    fun acceptPushedConfig(packageName: String, config: GlassConfig,
        revision: Long = -1L): Boolean {
        if (AppCatalog.forPackage(packageName) == null) return false
        val value = config.normalized(packageName)
        if (!committedConfigs.acceptVersioned(packageName, value, revision)) return false
        io.github.offlineglass.rendering.GlassEdgeHighlight.transparency = value.highlightTransparency
        // Adapter-owned storage is optional, not a prerequisite for accepting
        // a committed module snapshot. Otherwise most adapters discarded the
        // push here and the next poll/rebind restored an older fallback value.
        TargetAdapterRegistry.forPackage(packageName)?.acceptPushedConfig(value)
        val snapshot = Cached(packageName, value, System.currentTimeMillis())
        cached = snapshot
        fallbackCache = snapshot
        return true
    }

    private val lastSyncRequest = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val syncCallbacks = HashMap<String, MutableList<(GlassConfig?) -> Unit>>()
    private fun deliverSync(packageName: String) {
        val callbacks = synchronized(this) { syncCallbacks.remove(packageName)?.toList().orEmpty() }
        callbacks.forEach { it(pushedConfig(packageName)) }
    }
    /** Explicit ordered request: recover a missed push without toggling UI settings. */
    @Synchronized
    fun requestConfigSync(context: Context, packageName: String,
        complete: ((GlassConfig?) -> Unit)? = null) {
        if (AppCatalog.forPackage(packageName) == null) return
        val now = android.os.SystemClock.uptimeMillis()
        val previous = lastSyncRequest[packageName]
        if (previous != null && now - previous < 5_000L) {
            if (complete != null) {
                val pending = syncCallbacks[packageName]
                if (pending != null) pending.add(complete)
                else mainHandler.post { complete(pushedConfig(packageName)) }
            }
            return
        }
        lastSyncRequest[packageName] = now
        // Drop callbacks of a timed-out request; bound retained closures.
        syncCallbacks[packageName] = mutableListOf<(GlassConfig?) -> Unit>().apply {
            if (complete != null) add(complete)
        }
        val app = context.applicationContext ?: context
        runCatching {
            app.sendOrderedBroadcast(android.content.Intent(ConfigContract.ACTION_CONFIG_REQUEST)
                .setComponent(android.content.ComponentName(MODULE_PACKAGE,
                    "$MODULE_PACKAGE.config.ConfigRequestReceiver"))
                .addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND or
                    android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(ConfigContract.EXTRA_REQUEST_PACKAGE, packageName), null,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                        val reply = getResultExtras(false)
                        if (reply == null || resultCode != android.app.Activity.RESULT_OK ||
                            reply.getString(ConfigContract.KEY_PACKAGE) != packageName) {
                            deliverSync(packageName)
                            return
                        }
                        acceptPushedConfig(packageName, GlassConfig.fromBundle(reply),
                            revision = reply.getLong(ConfigContract.KEY_REVISION, -1L))
                        deliverSync(packageName)
                        android.util.Log.i("LiquidTabConfig", "sync reply pkg=$packageName revision=" +
                            reply.getLong(ConfigContract.KEY_REVISION, -1L))
                    }
                }, mainHandler, android.app.Activity.RESULT_CANCELED, null, null)
        }.onFailure { android.util.Log.w("LiquidTabConfig", "sync request failed pkg=$packageName", it) }
    }

    /**
     * Reads current config — prefers the module's exported SettingsProvider
     * and falls back to XSharedPreferences.
     *
     * LocalSettings exports the writer through xposedsharedprefs and imports
     * existing CE/DE settings by revision. Validated framework reads can decide
     * startup when target-package visibility or background policy blocks IPC.
     */
    private fun readCurrent(context: Context, packageName: String): GlassConfig {
        // Always give authoritative IPC a chance to advance the revision.
        // Only the disk/default fallback is subordinate to a committed push.
        val polled = readUnpushedCurrent(context, packageName)
        return pushedConfig(packageName) ?: polled
    }

    private fun readUnpushedCurrent(context: Context, packageName: String): GlassConfig {
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
        val value = readThroughLsposed(packageName, commitStartup = true) ?: pushedConfig(packageName)
            ?: GlassConfig(enabled = false, liquidGlassEnabled = false)
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
            if (!bundle.containsKey(ConfigContract.KEY_REVISION)) return@runCatching null
            val parsed = GlassConfig.fromBundle(bundle)
            acceptPushedConfig(packageName, parsed,
                revision = bundle.getLong(ConfigContract.KEY_REVISION, -1L))
            android.util.Log.i("LiquidTabStartup", "SOURCE provider pkg=$packageName revision=" +
                bundle.getLong(ConfigContract.KEY_REVISION, -1L))
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

    private fun readThroughLsposed(packageName: String, commitStartup: Boolean = false): GlassConfig? = runCatching {
        val p = io.github.offlineglass.hook.compat.XposedBridge.framework().getRemotePreferences(PREF_FILE)
        // XSharedPreferences may return an empty map without throwing when CE
        // preferences are unreadable. Such a read is not a factory-default choice.
        if (!p.contains("revision")) {
            if (commitStartup) startupPreferenceStatus = "unreadable-or-no-revision"
            return@runCatching null
        }
        val revision = p.getLong("revision", -1L)
        if (revision < 0L) {
            if (commitStartup) startupPreferenceStatus = "invalid-revision=$revision"
            return@runCatching null
        }
        if (!StartupPreferencesPolicy.usable(true, revision,
                p.contains(ConfigContract.KEY_LIQUID_GLASS_ENABLED))) {
            if (commitStartup) startupPreferenceStatus = "incomplete-global-settings revision=$revision"
            return@runCatching null
        }
        val global = readConfig(p, GLOBAL, GlassConfig())
            .copy(
                liquidGlassEnabled = p.getBoolean(ConfigContract.KEY_LIQUID_GLASS_ENABLED, true),
                bottomGradientBlurEnabled = false,
                solidBarEnabled = p.getBoolean(ConfigContract.KEY_SOLID_BAR_ENABLED, false),
                outlineEnabled = p.getBoolean(ConfigContract.KEY_OUTLINE_ENABLED, true),
            )
        val spec = AppCatalog.forPackage(packageName)
        val canonicalPackage = spec?.packageName ?: packageName
        // An absent app key means the module's own default (enabled), exactly
        // as LocalSettings.configFor does. Do not strand newly adapted apps.
        val defaults = global.copy(
            iconOnly = (spec?.defaultIconOnly == true) || global.iconOnly,
            hiddenMask = global.hiddenMask or (spec?.defaultHiddenMask ?: 0),
            customAccentColor = spec?.defaultAccentColor ?: global.customAccentColor,
            detachedPublish = (spec?.defaultDetachedPublish == true) || global.detachedPublish,
        )
        val config = if (p.getBoolean("appcfg_${canonicalPackage}_override", false)) {
            readConfig(p, "appcfg_${canonicalPackage}_", defaults)
        } else {
            defaults
        }
        val value = config.copy(
            enabled = p.getBoolean("app_$canonicalPackage", true),
            blurRadius = global.blurRadius,
            cornerRadiusPercent = 50f,
            cornerSmoothing = global.cornerSmoothing,
            barHeight = global.barHeight,
            lightAlpha = global.lightAlpha,
            darkAlpha = global.darkAlpha,
            darkBarHighlightStrength = global.darkBarHighlightStrength,
            highlightTransparency = global.highlightTransparency,
            bottomGradientBlurEnabled = false,
        ).normalized(canonicalPackage)
        if (commitStartup) {
            val accepted = acceptPushedConfig(packageName, value, revision = revision)
            startupPreferenceStatus = "accepted=$accepted revision=$revision enabled=${value.enabled} " +
                "detached=${value.detachedPublish} blur=${value.blurRadius} classic=${value.classicNavigation}"
        }
        value
    }.onFailure {
        if (commitStartup) startupPreferenceStatus = "error=${it.javaClass.name}:${it.message}"
        android.util.Log.w("LiquidTabStartup", "Preferences unavailable pkg=$packageName", it)
    }
        .getOrNull()

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
        showPostButton = p.getBoolean(prefix + ConfigContract.KEY_SHOW_POST, defaults.showPostButton),
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
        managerEnglishNavigationLabels = if (p.contains("manager_language"))
            io.github.offlineglass.config.ManagerLanguage.fromValue(p.getString("manager_language", null)) ==
                io.github.offlineglass.config.ManagerLanguage.ENGLISH
        else p.getBoolean(GLOBAL + ConfigContract.KEY_MANAGER_ENGLISH_LABELS, false),
        tiktokPinnedTabs = p.getString(prefix + ConfigContract.KEY_TIKTOK_PINNED_TABS, defaults.tiktokPinnedTabs).orEmpty(),
        douyinPinnedTabs = p.getString(prefix + ConfigContract.KEY_DOUYIN_PINNED_TABS, defaults.douyinPinnedTabs).orEmpty(),
        tiktokTallVideoFill = p.getBoolean(prefix + ConfigContract.KEY_TIKTOK_TALL_VIDEO_FILL, defaults.tiktokTallVideoFill),
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

    private const val MODULE_PACKAGE = "io.github.offlineglass"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val PREF_FILE = "offline_glass"
    private const val GLOBAL = "glass_"
    private const val PROVIDER_FAILURE_BACKOFF_MS = 5_000L
}
