package io.github.offlineglass.hook.adapters.chatgpt

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.offlineglass.config.ConfigContract
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader

/** Same protected configuration broadcasts as the shared glass host; no polling or sensors. */
internal object ChatGptLiveConfig {
    private var installed = false
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext ?: context
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val bundle = intent?.extras ?: return
                if (bundle.getString(ConfigContract.KEY_PACKAGE) != "com.openai.chatgpt") return
                HookConfigReader.acceptPushedConfig("com.openai.chatgpt", GlassConfig.fromBundle(bundle),
                    revision = bundle.getLong(ConfigContract.KEY_REVISION, -1L))
                ChatGptComposerGlass.refreshConfig()
            }
        }, IntentFilter(ConfigContract.ACTION_CONFIG_RESPONSE), ConfigContract.CONFIG_SYNC_PERMISSION,
            null, Context.RECEIVER_EXPORTED)
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                ChatGptComposerGlass.refreshConfig(appearanceChanged = true)
            }
        }, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), Context.RECEIVER_EXPORTED)
        installed = true
        HookConfigReader.requestConfigSync(app, "com.openai.chatgpt") { ChatGptComposerGlass.refreshConfig() }
    }
}
