package io.github.offlineglass.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.offlineglass.targets.AppCatalog

/** Replies with current settings when a hooked app cannot resolve the provider. */
class ConfigRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfigContract.ACTION_CONFIG_REQUEST) return
        val packageName = intent.getStringExtra(ConfigContract.EXTRA_REQUEST_PACKAGE).orEmpty()
        if (AppCatalog.forPackage(packageName) == null) return
        LocalSettings.publishConfig(context, packageName)
    }
}
