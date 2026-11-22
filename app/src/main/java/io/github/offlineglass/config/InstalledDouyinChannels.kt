package io.github.offlineglass.config

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.zip.ZipFile

/** Reads native enum constructor arguments from the installed APK, without
 * loading/executing Douyin classes or starting its process. Verified on 40.8:
 * native top-channel enum has getId/getTitle and constructor (I,String,String,I,String,String,String).
 * Class names and multidex positions are discovered, not version-hardcoded.
 */
object InstalledDouyinChannels {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-DouyinApk").apply { isDaemon = true }
    }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var pending = false

    fun refresh(context: Context, complete: () -> Unit) {
        if (pending) return
        val app = context.applicationContext
        pending = true
        worker.execute {
            runCatching {
                val info = app.packageManager.getPackageInfo("com.ss.android.ugc.aweme", 0)
                val apk = app.packageManager.getApplicationInfo("com.ss.android.ugc.aweme", 0).sourceDir
                val stamp = "$apk:${info.lastUpdateTime}"
                val cache = app.getSharedPreferences("douyin_apk_channels", Context.MODE_PRIVATE)
                if (cache.getString("stamp", "") == stamp) return@runCatching
                val channels = scanApk(apk)
                if (channels.size > 3) {
                    LocalSettings.reportDouyinTopChannels(app, DouyinTopChannels.encode(channels))
                    cache.edit().putString("stamp", stamp).apply()
                    java.io.File(app.filesDir, "douyin-apk-catalog-status.txt")
                        .writeText("time=${System.currentTimeMillis()} channels=${channels.size} source=$stamp\n")
                }
            }.onFailure {
                android.util.Log.w("LiquidTab", "Douyin APK channel read failed", it)
                runCatching { java.io.File(app.filesDir, "douyin-apk-catalog-status.txt").writeText(it.stackTraceToString()) }
            }
            main.post { pending = false; complete() }
        }
    }

    fun scanApk(path: String): List<DouyinTopChannel> {
        val found = linkedMapOf<String, String>()
        val verified = listOf("homepage_mediumvideo" to "精选", "homepage_groupon" to "团购")
        val presentIds = HashSet<String>()
        val presentTitles = HashSet<String>()
        ZipFile(path).use { apk ->
            val entries = apk.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!Regex("classes\\d*\\.dex").matches(entry.name)) continue
                val bytes = apk.getInputStream(entry).use { it.readBytes() }
                // Cheap prefilter; only decode the string pool containing the native catalog.
                val probe = String(bytes, Charsets.ISO_8859_1)
                for ((id, title) in verified) {
                    if (probe.contains("$id\u0000")) presentIds += id
                    if (probe.contains(String(title.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)+"\u0000")) presentTitles += title
                }
                if (!probe.contains("HOMEPAGE_NEARBY")) continue
                val dex = Dex(bytes)
                dex.channels().forEach { found[canonical(it.id)] = it.label }
                // These two dynamic phone channels are outside the enum. Their
                // bindings were verified against 40.8 HomeTopTabItemViewNew.
            }
        }
        for ((id, title) in verified) if (id in presentIds && title in presentTitles) found[id] = title
        val fixed = listOf(DouyinTopChannel("homepage_hot_container", "推荐"),
            DouyinTopChannel("homepage_tablive", "直播"), DouyinTopChannel("homepage_follow", "关注"))
        return fixed + found.filterKeys { id -> fixed.none { it.id == id } }.map { DouyinTopChannel(it.key, it.value) }
    }

    private fun canonical(id: String) = if (id == "homepage_hot") "homepage_hot_container" else id

    private class Dex(val bytes: ByteArray) {
        fun u16(p: Int) = (bytes[p].toInt() and 255) or ((bytes[p+1].toInt() and 255) shl 8)
        fun i32(p: Int) = u16(p) or (u16(p+2) shl 16)
        fun uleb(start: Int): Pair<Int, Int> {
            var p = start; var value = 0; var shift = 0
            do { val b = bytes[p++].toInt() and 255; value = value or ((b and 127) shl shift); shift += 7 } while (b and 128 != 0)
            return value to p
        }
        val strings: List<String> = List(i32(56)) { i ->
            val p = uleb(i32(i32(60)+i*4)).second
            var end = p
            while (bytes[end] != 0.toByte()) end++
            String(bytes, p, end-p, Charsets.UTF_8)
        }
        fun hasString(value: String) = value in strings
        fun type(index: Int) = strings[i32(i32(68)+index*4)]
        fun methodName(index: Int) = strings[i32(i32(92)+index*8+4)]
        fun methodType(index: Int) = u16(i32(92)+index*8)
        fun constructor(index: Int): Boolean {
            if (methodName(index) != "<init>") return false
            val proto = i32(76)+u16(i32(92)+index*8+2)*12
            val p = i32(proto+8)
            if (p == 0 || i32(p) != 7) return false
            return (0..6).map { type(u16(p+4+it*2)) } == listOf("I", "Ljava/lang/String;", "Ljava/lang/String;", "I",
                "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;")
        }

        fun channels(): List<DouyinTopChannel> {
            val result = ArrayList<DouyinTopChannel>()
            for (index in 0 until i32(96)) {
                val cd = i32(100)+index*32
                if (i32(cd+4) and 0x4000 == 0) continue // native enum only
                val owner = i32(cd)
                var p = i32(cd+24)
                if (p == 0) continue
                fun next(): Int { val item = uleb(p); p = item.second; return item.first }
                val sf = next(); val fields = next(); val direct = next(); val virtual = next()
                repeat(sf+fields) { next(); next() }
                val methods = ArrayList<Pair<Int, Int>>()
                for (count in listOf(direct, virtual)) {
                    var method = 0
                    repeat(count) { method += next(); next(); methods += method to next() }
                }
                if (methods.none { methodName(it.first) == "getId" } || methods.none { methodName(it.first) == "getTitle" }) continue
                val init = methods.firstOrNull { methodName(it.first) == "<clinit>" }?.second ?: continue
                val registers = arrayOfNulls<String>(u16(init))
                val count = i32(init+12)
                var unit = 0
                while (unit < count) {
                    val at = init+16+unit*2
                    val word = u16(at); val op = word and 255; val a = word ushr 8
                    when (op) {
                        0x1a -> registers[a] = strings[u16(at+2)]
                        0x1b -> registers[a] = strings[i32(at+2)]
                        0x07 -> registers[(word ushr 8) and 15] = registers[word ushr 12]
                        0x08 -> registers[a] = registers[u16(at+2)]
                        0x09 -> registers[u16(at+2)] = registers[u16(at+4)]
                        0x0a, 0x0b, 0x0c, 0x0d, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1c, 0x22 -> registers[a] = null
                        0x12 -> registers[(word ushr 8) and 15] = null
                        0x76 -> {
                            val method = u16(at+2); val start = u16(at+4)
                            if (methodType(method) == owner && constructor(method) && a == 8) {
                                val id = registers.getOrNull(start+3)
                                val title = registers.getOrNull(start+5)
                                if (id?.startsWith("homepage_") == true && title != null && title.length <= 16 &&
                                    title.any { it in '\u4e00'..'\u9fff' }) result += DouyinTopChannel(id, title)
                            }
                        }
                    }
                    unit += width(op, word, at)
                }
            }
            return result.distinctBy { it.id }
        }

        fun width(op: Int, word: Int, at: Int): Int = when {
            op == 0 && word ushr 8 == 1 -> 4+2*u16(at+2)
            op == 0 && word ushr 8 == 2 -> 2+4*u16(at+2)
            op == 0 && word ushr 8 == 3 -> 4+(u16(at+2)*i32(at+4)+1)/2
            op == 0x18 -> 5
            op == 0xfa || op == 0xfb -> 4
            op in listOf(3,6,9,0x14,0x17,0x1b,0x24,0x25,0x26,0x2a,0x2b,0x2c,0xfc,0xfd) || op in 0x6e..0x72 || op in 0x74..0x78 -> 3
            op in listOf(2,5,8,0x13,0x15,0x16,0x19,0x1a,0x1c,0x1f,0x20,0x22,0x23,0x29,0xfe,0xff) ||
                op in 0x2d..0x3d || op in 0x44..0x6d || op in 0x90..0xaf || op in 0xd0..0xe2 -> 2
            else -> 1
        }
    }
}
