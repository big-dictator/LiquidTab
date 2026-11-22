package io.github.offlineglass.config

/** Module UI choice and the source of truth for supported target navigation labels. */
enum class ManagerLanguage(val value: String) {
    CHINESE("zh"), ENGLISH("en");
    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: CHINESE
    }
}
