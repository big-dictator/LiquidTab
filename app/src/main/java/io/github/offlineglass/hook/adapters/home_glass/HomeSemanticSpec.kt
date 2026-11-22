package io.github.offlineglass.hook.adapters.home_glass

/** Target-owned labels; the reader contains no package, channel, or routing policy. */
internal data class HomeSemanticSpec(
    val channels: List<String>,
    val avatar: List<String>,
    val account: List<String>,
    val searchIcon: List<String>,
    val search: List<String>,
    val message: List<String> = emptyList(),
    val game: List<String> = emptyList(),
    val requireMessage: Boolean = false,
)
