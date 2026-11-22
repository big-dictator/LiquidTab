package io.github.offlineglass.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import java.io.File
import org.xmlpull.v1.XmlPullParser

/** Import existing module settings before initializing a framework-exported file. */
internal object SharedPreferenceMigration {
    private val checked = HashSet<String>()

    @Synchronized
    fun importLatest(context: Context, destination: SharedPreferences, name: String) {
        val home = context.filesDir.parentFile ?: return
        if (!checked.add(home.absolutePath + "/" + name)) return
        val candidates = linkedSetOf(File(home, "shared_prefs/$name.xml"))
        // Produced by the legacy upgrade bridge while the framework's redirected
        // directory was still available. Select by revision, never by file age.
        candidates.add(File(context.filesDir, "modern-upgrade-settings.xml"))
        runCatching { context.createDeviceProtectedStorageContext().filesDir.parentFile }
            .getOrNull()?.let { candidates.add(File(it, "shared_prefs/$name.xml")) }
        var revision = destination.getLong("revision", -1L)
        var selected: Map<String, Any>? = null
        for (file in candidates) {
            if (!file.isFile || file.length() > 2 * 1024 * 1024) continue
            val values = runCatching { read(file) }.getOrElse {
                android.util.Log.w("LiquidTabConfig", "Legacy settings import failed", it)
                continue
            }
            val candidateRevision = values["revision"] as? Long ?: continue
            if (candidateRevision <= revision) continue
            revision = candidateRevision
            selected = values
        }
        val values = selected ?: return
        val editor = destination.edit().clear()
        for ((key, value) in values) when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
        check(editor.commit()) { "Unable to preserve existing module settings" }
        check(destination.getLong("revision", -1L) == revision)
    }

    private fun read(file: File): Map<String, Any> = file.inputStream().use { input ->
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val values = LinkedHashMap<String, Any>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val key = parser.getAttributeValue(null, "name") ?: continue
            val scalar = parser.getAttributeValue(null, "value")
            val value: Any = when (parser.name) {
                "string" -> parser.nextText()
                "boolean" -> requireNotNull(scalar).toBooleanStrict()
                "int" -> requireNotNull(scalar).toInt()
                "long" -> requireNotNull(scalar).toLong()
                "float" -> requireNotNull(scalar).toFloat()
                "set" -> {
                    val strings = LinkedHashSet<String>()
                    val depth = parser.depth
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) break
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") strings.add(parser.nextText())
                    }
                    strings
                }
                else -> continue
            }
            values[key] = value
        }
        values
    }
}
