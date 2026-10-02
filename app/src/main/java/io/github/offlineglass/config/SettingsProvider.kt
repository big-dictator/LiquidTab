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
        context?.let { LocalSettings.applyFormalDefaultsOnce(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        if (method == ConfigContract.METHOD_GET) {
            android.util.Log.i(
                "LiquidTabUI",
                "providerCall isDE=${context.isDeviceProtectedStorage} " +
                    "appIsDE=${context.applicationContext?.isDeviceProtectedStorage} " +
                    "dir=${context.filesDir?.parent}",
            )
        }
        return when (method) {
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
                LocalSettings.configFor(context, packageName).toBundle(packageName).apply {
                    // The provider runs in the module's own process, whose
                    // configuration still tracks the real device state even
                    // when a hooked app's process is pinned to the day
                    // configuration by HyperOS force-dark.
                    putBoolean(ConfigContract.KEY_SYSTEM_DARK, isSystemDarkNow())
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
