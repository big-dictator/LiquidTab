package io.github.offlineglass.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Private local settings stay authoritative; atomically mirror their revision to the framework. */
object ModernSettingsBridge {
    @Volatile private var service: XposedService? = null
    @Volatile private var started = false
    private var publishedRevision = Long.MIN_VALUE
    private val mirrorLock = Any()

    @Synchronized fun initialize(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(value: XposedService) {
                synchronized(LocalSettings) {
                    synchronized(mirrorLock) {
                        service = value
                        publishedRevision = Long.MIN_VALUE
                    }
                    LocalSettings.applyFormalDefaultsOnce(app)
                    publish(LocalSettings.exportPreferences(app))
                }
            }
            override fun onServiceDied(value: XposedService) {
                synchronized(mirrorLock) {
                    if (service === value) {
                        service = null
                        publishedRevision = Long.MIN_VALUE
                    }
                }
            }
        })
    }

    /** Serialized independently of UI commits. Copy one consistent local revision. */
    internal fun publish(prefs: SharedPreferences): Unit = synchronized(mirrorLock) {
        val current = service ?: return
        val snapshot = prefs.all
        val revision = snapshot["revision"] as? Long ?: -1L
        if (revision < 0L || revision == publishedRevision) return
        runCatching {
            val remote = current.getRemotePreferences("offline_glass")
            val edit = remote.edit().clear()
            snapshot.forEach { (key, value) ->
                when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(edit.commit()) { "Framework settings commit failed" }
            publishedRevision = revision
            Log.i("LiquidTabModern", "remote settings published revision=$revision")
        }.onFailure { Log.e("LiquidTabModern", "Remote settings export failed; local settings retained", it) }
    }
}
