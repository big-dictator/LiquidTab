package io.github.offlineglass.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

class SettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Also covers upgrades where the manager UI is not opened before a
        // hooked application requests its configuration.
        context?.let {
            LocalSettings.applyFormalDefaultsOnce(it)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_glass_edge_color") {
            return io.github.offlineglass.rendering.GlassEdgeColorProbe.run()
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_gpu_halo") {
            return io.github.offlineglass.rendering.GpuHaloProbe.run()
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_outer_light_translation") {
            return io.github.offlineglass.rendering.IndicatorInteractionLight.verifyTranslation()
        }
        val context = requireNotNull(context)
        if(io.github.offlineglass.BuildConfig.DEBUG && method==io.github.offlineglass.hook.adapters.wechat.WeChatPreviewDiagnostics.REPORT) {
            val callers=context.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
            val accepted="com.tencent.mm" in callers
            if(accepted && extras!=null)context.getSharedPreferences("wechat_preview_status",android.content.Context.MODE_PRIVATE).edit().apply {
                putInt("pid",extras.getInt("pid"))
                io.github.offlineglass.hook.adapters.wechat.WeChatPreviewDiagnostics.Stage.entries.forEach {
                    val name="n_${it.ordinal}";putInt(name,extras.getInt(name).coerceIn(0,1_000_000_000))
                }
            }.apply()
            return Bundle().apply { putBoolean("accepted",accepted) }
        }
        if(io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid()==2000 &&
            method==io.github.offlineglass.hook.adapters.wechat.WeChatPreviewDiagnostics.READ) {
            val status=context.getSharedPreferences("wechat_preview_status",android.content.Context.MODE_PRIVATE)
            return Bundle().apply {
                putInt("pid",status.getInt("pid",0))
                io.github.offlineglass.hook.adapters.wechat.WeChatPreviewDiagnostics.Stage.entries.forEach {
                    putInt(it.name,status.getInt("n_${it.ordinal}",0))
                }
            }
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_contour_gpu") {
            return io.github.offlineglass.rendering.ContourGpuProbe.run()
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_bounded_contour_gpu") {
            return io.github.offlineglass.rendering.BoundedContourGpuProbe.run()
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_exact_optical_work") {
            return io.github.offlineglass.rendering.verifyExactOpticalWork()
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_manager_motion") {
            return io.github.offlineglass.ui.verifyManagerBottomBarMotion()
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_blur_edges") {
            return runCatching {
                io.github.offlineglass.rendering.BlurEdgeProbe.run(java.io.File(context.filesDir, "blur-edge-probe"))
            }.getOrElse { Bundle().apply { putString("error", it.stackTraceToString()) } }
        }
        if (io.github.offlineglass.BuildConfig.DEBUG && android.os.Binder.getCallingUid() == 2000 &&
            method == "debug_verify_posture_shaders") {
            return Bundle().apply {
                val failures = arrayListOf<String>()
                val enabledTargets = arrayListOf<String>()
                val excludedTargets = arrayListOf<String>()
                io.github.offlineglass.targets.TargetSpecRegistry.all.forEach { spec ->
                    val flowing = io.github.offlineglass.hook.adapters.TargetAdapterRegistry
                        .forSpec(spec)?.flowingLiquidEnabled == true
                    if (flowing) enabledTargets.add(spec.key) else excludedTargets.add(spec.key)
                    if (flowing != (spec.key !in setOf("jd", "taobao")))
                        failures.add("Flowing policy mismatch: ${spec.key}")
                    val adapter = io.github.offlineglass.hook.adapters.TargetAdapterRegistry.forSpec(spec)
                    if (adapter?.unifiedDropletPilot == true) failures.add("Rejected droplet pilot still enabled: ${spec.key}")
                    if (adapter?.directionalRimPilot == true)
                        failures.add("Rejected rim pilot still enabled: ${spec.key}")
                    if ((io.github.offlineglass.hook.adapters.TargetAdapterRegistry
                        .forSpec(spec)?.liquidMotionEnabled == true) != (spec.key !in setOf("jd", "taobao")))
                        failures.add("Liquid motion scope mismatch: ${spec.key}")
                }
                val sources = listOf(
                    "shared" to io.github.offlineglass.rendering.KYANT_LENS_SHADER,
                    "legacy-excluded" to io.github.offlineglass.rendering.PostureGlass.wrap(
                        io.github.offlineglass.rendering.KYANT_LENS_SHADER, flowing = false),
                    "spotify-flowing" to io.github.offlineglass.rendering.FlowingLiquid.kyantLensSource(
                        io.github.offlineglass.rendering.KYANT_LENS_SHADER),
                    "outer" to io.github.offlineglass.hook.GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER,
                    "indicator" to io.github.offlineglass.hook.GlassHostLayout.ROUNDED_RECT_DISPERSION_SHADER,
                )
                sources.forEach { (name, source) ->
                    runCatching { io.github.offlineglass.rendering.PostureGlass.shader(source) }
                        .onFailure { failures.add("$name: ${it.message}") }
                }
                runCatching { io.github.offlineglass.ui.verifyManagerPostureShaders() }
                    .onFailure { failures.add("manager: ${it.message}") }
                runCatching {
                    val bloom = android.graphics.RuntimeShader(
                        io.github.offlineglass.hook.GlassHostLayout.BLOOM_STROKE_DUAL_SHADER)
                    for (band in 0..4) bloom.setFloatUniform("featherBand", band.toFloat())
                }.onFailure { failures.add("panel bloom: ${it.message}") }
                runCatching {
                    io.github.offlineglass.rendering.LiquidMotion.verify()
                    io.github.offlineglass.rendering.LiquidMotion.verifyAxes(android.view.View(context))
                    io.github.offlineglass.ui.verifyViscosityDetents()
                    io.github.offlineglass.ui.verifyManagerSliderGesture()
                    for (value in listOf(0f, 25f, 100f)) {
                        check(GlassConfig.fromBundle(GlassConfig(liquidViscosity = value)
                            .toBundle("com.spotify.music")).liquidViscosity == value)
                    }
                    check(GlassConfig(liquidViscosity = Float.NaN).normalized().liquidViscosity == 25f)
                    for (x in listOf(0f, 50f, 100f)) for (y in listOf(0f, 50f, 100f)) {
                        val restored = GlassConfig.fromBundle(GlassConfig(
                            flowHorizontalStrength = x, flowVerticalStrength = y).toBundle("com.spotify.music"))
                        check(restored.flowHorizontalStrength == x && restored.flowVerticalStrength == y)
                    }
                    check(GlassConfig.fromBundle(android.os.Bundle()).flowHorizontalStrength == 100f)
                    for (strength in listOf(0f, 100f, 150f, 200f)) {
                        val restored = GlassConfig.fromBundle(GlassConfig(refractionStrength = strength)
                            .toBundle("com.xingin.xhs"))
                        check(restored.refractionStrength == strength && restored.refractionScale == strength / 100f)
                    }
                    check(GlassConfig.fromBundle(Bundle()).refractionStrength == GlassConfig.DEFAULT_REFRACTION_STRENGTH)
                    check(GlassConfig(refractionStrength = Float.NaN).normalized().refractionStrength == GlassConfig.DEFAULT_REFRACTION_STRENGTH)
                    check(GlassConfig(refractionStrength = 250f).normalized().refractionStrength == 200f)
                    check(GlassConfig(refractionStrength = -1f).normalized().refractionStrength == 0f)
                    check(GlassConfig().blurRadius == 0f && GlassConfig.fromBundle(Bundle()).blurRadius == 0f)
                    for (alpha in listOf(0f, 0.1f, 0.4f, 0.5f, 0.8f)) {
                        val c = GlassConfig(lightAlpha = alpha, darkAlpha = 0.123f).normalized()
                        check(c.lightAlpha == alpha && c.darkAlpha == alpha)
                        check(c.withSurfaceOpacity(android.graphics.Color.TRANSPARENT) == android.graphics.Color.TRANSPARENT)
                        val contrasts = listOf(false, true).map { dark ->
                            val pixels = android.graphics.Bitmap.createBitmap(2, 1, android.graphics.Bitmap.Config.ARGB_8888)
                            try {
                                val canvas = android.graphics.Canvas(pixels)
                                val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
                                canvas.drawRect(0f, 0f, 1f, 1f, paint)
                                paint.color = android.graphics.Color.WHITE
                                canvas.drawRect(1f, 0f, 2f, 1f, paint)
                                paint.color = c.surfaceTintColor(dark)
                                canvas.drawRect(0f, 0f, 2f, 1f, paint)
                                android.graphics.Color.red(pixels.getPixel(1, 0)) - android.graphics.Color.red(pixels.getPixel(0, 0))
                            } finally { pixels.recycle() }
                        }
                        check(contrasts[0] == contrasts[1]) { "Theme changed real backdrop transmission" }
                    }
                    check(GlassConfig(flowHorizontalStrength = -1f, flowVerticalStrength = 101f)
                        .normalized().let { it.flowHorizontalStrength == 0f && it.flowVerticalStrength == 100f })
                }.onFailure { failures.add("liquid-motion: ${it.message}") }
                putString("failureDetails", failures.joinToString("; "))
                putBoolean("passed", failures.isEmpty())
                putStringArrayList("flowingTargets", enabledTargets)
                putStringArrayList("excludedTargets", excludedTargets)
                putStringArrayList("failures", failures)
            }
        }
        if (method == ConfigContract.METHOD_GET) {
            android.util.Log.i(
                "LiquidTabUI",
                "providerCall isDE=${context.isDeviceProtectedStorage} " +
                    "appIsDE=${context.applicationContext?.isDeviceProtectedStorage} " +
                    "dir=${context.filesDir?.parent}",
            )
        }
        return when (method) {
            ConfigContract.METHOD_DOUYIN_TOP_TABS -> {
                val callers = context.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
                val accepted = "com.ss.android.ugc.aweme" in callers && arg == "com.ss.android.ugc.aweme"
                if (accepted) extras?.getString(ConfigContract.KEY_DOUYIN_TOP_TABS)?.let {
                    LocalSettings.reportDouyinTopChannels(context, it)
                }
                Bundle().apply { putBoolean("accepted", accepted) }
            }
            ConfigContract.METHOD_SPOTIFY_TABS -> {
                val callers = context.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
                val accepted = "com.spotify.music" in callers && arg == "com.spotify.music" && extras != null
                if (accepted) {
                    val changed = extras!!.getInt("changed_mask") and 24
                    val current = LocalSettings.configFor(context, "com.spotify.music")
                    val hidden = extras.getInt(ConfigContract.KEY_HIDDEN_MASK)
                    val available = extras.getBoolean(ConfigContract.KEY_SPOTIFY_PREMIUM_AVAILABLE, true)
                    val next = current.copy(hiddenMask = (current.hiddenMask and changed.inv()) or (hidden and changed),
                        spotifyPremiumAvailable = available,
                        spotifyCreateCommand = current.spotifyCreateCommand && changed and 16 == 0)
                    if (next != current) LocalSettings.setAppConfig(context, "com.spotify.music", next, spotifyNativeOrigin = changed and 16 != 0)
                }
                Bundle().apply { putBoolean("accepted", accepted) }
            }
            ConfigContract.METHOD_REPORT_NAVIGATION -> {
                val packageName = arg.orEmpty()
                val callers = context.packageManager.getPackagesForUid(android.os.Binder.getCallingUid()).orEmpty()
                if (packageName in callers) {
                    extras?.getStringArrayList(ConfigContract.KEY_NAVIGATION_LABELS)?.let {
                        LocalSettings.reportNavigationLabels(context, packageName, it)
                    }
                }
                Bundle.EMPTY
            }
            ConfigContract.METHOD_GET -> {
                val packageName = arg.orEmpty()
                LocalSettings.configSnapshot(context, packageName).apply {
                    // The provider runs in the module's own process, whose
                    // configuration still tracks the real device state even
                    // when a hooked app's process is pinned to the day
                    // configuration by HyperOS force-dark.
                    putBoolean(ConfigContract.KEY_SYSTEM_DARK, SystemTheme.isDark(context))
                }
            }

            ConfigContract.METHOD_REPORT_ACTIVE -> {
                arg?.takeIf { it.isNotBlank() }?.let { LocalSettings.reportActive(context, it) }
                Bundle.EMPTY
            }

            ConfigContract.METHOD_SNAPSHOT -> {
                val settings = LocalSettings.snapshot(context)
                Bundle().apply {
                    putString(ConfigContract.KEY_LAST_ACTIVE_PACKAGE, settings.lastActivePackage)
                    putLong(ConfigContract.KEY_LAST_ACTIVE_TIME, settings.lastActiveTime)
                }
            }

            ConfigContract.METHOD_MI_MARKET_TAB_GET -> {
                Bundle().apply {
                    putBoolean(
                        ConfigContract.KEY_MI_MARKET_TAB_ENABLED,
                        LocalSettings.miMarketTabEnabled(context, arg.orEmpty()),
                    )
                }
            }

            ConfigContract.METHOD_MI_MARKET_TAB_SET -> {
                val enabled = extras?.getBoolean(ConfigContract.KEY_MI_MARKET_TAB_ENABLED, true) ?: true
                LocalSettings.setMiMarketTabEnabled(context, arg.orEmpty(), enabled)
                Bundle.EMPTY
            }

            else -> super.call(method, arg, extras) ?: Bundle.EMPTY
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val packageName = selectionArgs?.firstOrNull().orEmpty()
        val config = LocalSettings.configFor(requireNotNull(context), packageName)
        return MatrixCursor(arrayOf("package", "enabled", "blur", "light_alpha", "dark_alpha")).apply {
            addRow(arrayOf<Any?>(
                packageName,
                if (config.enabled) 1 else 0,
                config.blurRadius,
                config.lightAlpha,
                config.darkAlpha,
            ))
        }
    }

    private fun isSystemDarkNow(): Boolean {
        val mask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val night = android.content.res.Configuration.UI_MODE_NIGHT_YES
        val appNight = context?.resources?.configuration?.uiMode?.and(mask) == night
        val deviceNight = android.content.res.Resources.getSystem().configuration.uiMode and mask == night
        return appNight || deviceNight
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.offlineglass.config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
