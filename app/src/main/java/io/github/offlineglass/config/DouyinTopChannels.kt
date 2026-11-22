package io.github.offlineglass.config

import org.json.JSONArray
import org.json.JSONObject

data class DouyinTopChannel(val id: String, val label: String)

/** First enabled channel is the rightmost pin. Native IDs survive city-label changes. */
object DouyinTopChannels {
    const val MAX_PINS = 3
    const val SETTINGS_ROUTE = "douyin/top-tabs"
    const val DEFAULT_PINS = "homepage_hot_container\nhomepage_tablive\nhomepage_follow"
    fun pins(value: String): List<String> = value.lineSequence().map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 128 }.distinct().take(MAX_PINS).toList()

    fun toggle(value: String, id: String, enabled: Boolean): String {
        val current = pins(value)
        return when {
            !enabled -> current.filterNot { it == id }
            id in current || current.size >= MAX_PINS -> current
            else -> current + id
        }.joinToString("\n")
    }

    fun ordered(channels: List<DouyinTopChannel>, value: String): List<DouyinTopChannel> {
        val selected = pins(value)
        // Keep a saved, temporarily unavailable entry switchable off.
        val all = channels + selected.filter { id -> channels.none { it.id == id } }
            .map { DouyinTopChannel(it, "$it（暂不可用）") }
        return selected.mapNotNull { id -> all.firstOrNull { it.id == id } } + all.filterNot { it.id in selected }
    }

    fun decode(value: String): List<DouyinTopChannel> = runCatching {
        val array = JSONArray(value)
        List(minOf(array.length(), 32)) { index ->
            val item = array.getJSONObject(index)
            DouyinTopChannel(item.getString("id").take(128), item.getString("label").take(40))
        }.filter { it.id.isNotBlank() && it.label.isNotBlank() }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun encode(channels: List<DouyinTopChannel>): String = JSONArray().apply {
        channels.take(32).forEach { channel -> put(JSONObject().put("id", channel.id).put("label", channel.label)) }
    }.toString()
}
