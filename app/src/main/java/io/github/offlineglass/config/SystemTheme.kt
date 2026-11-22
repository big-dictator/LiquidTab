package io.github.offlineglass.config

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources

/** Avoid app-local Resources: HyperOS can pin an application's theme to day. */
internal object SystemTheme {
    fun isDark(context: Context, fallback: Boolean? = null): Boolean {
        when (context.getSystemService(UiModeManager::class.java)?.nightMode) {
            UiModeManager.MODE_NIGHT_YES -> return true
            UiModeManager.MODE_NIGHT_NO -> return false
        }
        // For scheduled/automatic mode ask for the system's effective configuration.
        val global = runCatching {
            val service = ActivityManager::class.java.getDeclaredMethod("getService").invoke(null)
            service.javaClass.getMethod("getConfiguration").invoke(service) as? Configuration
        }.getOrNull()
        val night = global?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
        if (night == Configuration.UI_MODE_NIGHT_YES) return true
        if (night == Configuration.UI_MODE_NIGHT_NO) return false
        return fallback ?: (Resources.getSystem().configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }
}
