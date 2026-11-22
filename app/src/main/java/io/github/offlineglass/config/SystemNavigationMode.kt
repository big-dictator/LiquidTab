package io.github.offlineglass.config

import android.content.Context
import android.provider.Settings

/** Reads Android's system navigation mode instead of storing a user override. */
object SystemNavigationMode {
    fun usesNavigationKeys(context: Context): Boolean {
        val miuiGestures = runCatching {
            Settings.Global.getInt(context.contentResolver, "force_fsg_nav_bar", -1)
        }.getOrDefault(-1)
        if (miuiGestures >= 0) return miuiGestures == 0
        val systemResources = android.content.res.Resources.getSystem()
        val resourceId = runCatching {
            systemResources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        }.getOrDefault(0)
        if (resourceId != 0) {
            val mode = runCatching { systemResources.getInteger(resourceId) }.getOrDefault(-1)
            if (mode >= 0) return mode != 2
        }
        val secureMode = runCatching {
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1)
        }.getOrDefault(-1)
        return secureMode >= 0 && secureMode != 2
    }
}
