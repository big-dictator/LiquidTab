package io.github.offlineglass.config

import org.json.JSONArray
import org.json.JSONObject

data class TikTokTopChannel(val id: String, val label: String)

/** TikTok protocol tags verified from 47.4.3 TopTabLayoutAbility/05L7.
 * Nearby is always keyed by protocol, never by a user's current city text.
 */
object TikTokTopChannels {
    const val ROUTE = "tiktok/top-tabs"
    const val DEFAULT_PINS = "For You\nFollowing"
    const val MAX_PINS = 3
    fun canonical(id: String) = when (id) { "SHOP_MALL" -> "Shop"; "FRIENDS_TAB", "FRIENDS_FEED" -> "Friends"; else -> id }
    fun pins(value: String) = value.lineSequence().map(String::trim).filter { it.isNotBlank() }
        .map(::canonical).distinct().take(MAX_PINS).toList()
    fun toggle(value: String, id: String, enabled: Boolean): String {
        val current = pins(value)
        return (if (!enabled) current.filterNot { it == id } else if (id in current || current.size >= MAX_PINS) current else current+id).joinToString("\n")
    }
    fun ordered(channels: List<TikTokTopChannel>, value: String): List<TikTokTopChannel> {
        val selected = pins(value)
        val all = channels + selected.filter { id -> channels.none { it.id == id } }.map { TikTokTopChannel(it, it) }
        return selected.mapNotNull { id -> all.firstOrNull { it.id == id } }+all.filterNot { it.id in selected }
    }
    fun encode(channels: List<TikTokTopChannel>) = JSONArray().apply {
        channels.forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
    }.toString()
    fun decode(value: String): List<TikTokTopChannel> = runCatching {
        val array = JSONArray(value)
        List(minOf(32, array.length())) { i -> array.getJSONObject(i).let { TikTokTopChannel(canonical(it.getString("id")), it.getString("label")) } }.distinctBy { it.id }
    }.getOrDefault(emptyList())

}
