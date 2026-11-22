package io.github.offlineglass.hook.adapters.tiktok

import android.content.Context
import io.github.offlineglass.config.LocalSettings
import io.github.offlineglass.config.TikTokTopChannel
import io.github.offlineglass.config.TikTokTopChannels
import java.util.zip.ZipFile

object TikTokInstalledTopChannels {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "LiquidTab-TikTokCatalog").apply { isDaemon = true } }
    @Volatile private var pending = false
    fun refresh(context: Context, complete: () -> Unit) {
        if (pending) return
        pending = true
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val pm = app.packageManager
                val info = pm.getPackageInfo("com.zhiliaoapp.musically", 0)
                val apk = pm.getApplicationInfo("com.zhiliaoapp.musically", 0).sourceDir
                val stamp = "$apk:${info.lastUpdateTime}"
                val cache = app.getSharedPreferences("tiktok_top_apk", Context.MODE_PRIVATE)
                if (cache.getString("stamp", "") != stamp) {
                    val channels = scanApk(apk)
                    if (channels.size >= 2) {
                        LocalSettings.reportTikTokTopChannels(app, TikTokTopChannels.encode(channels))
                        cache.edit().putString("stamp", stamp).apply()
                    }
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { pending = false; complete() }
        }
    }
    fun scanApk(path: String): List<TikTokTopChannel> {
        val known = listOf("For You" to "推荐", "Following" to "关注", "Nearby" to "同城", "Friends" to "社区",
            "homepage_explore" to "探索", "Shop" to "商城", "Popular" to "热门", "Stem" to "STEM", "Live" to "直播", "Drama" to "短剧")
        val present = HashSet<String>()
        ZipFile(path).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (!Regex("classes\\d*\\.dex").matches(e.name)) continue
                val pool = zip.getInputStream(e).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                for ((id, _) in known) {
                    val aliases = when(id) { "Friends" -> listOf("FRIENDS_TAB", "FRIENDS_FEED"); "Shop" -> listOf("Shop", "SHOP_MALL"); else -> listOf(id) }
                    if (aliases.any { pool.contains(it+"\u0000") }) present += id
                }
            }
        }
        return known.filter { it.first in present }.map { TikTokTopChannel(it.first, it.second) }
    }
}
