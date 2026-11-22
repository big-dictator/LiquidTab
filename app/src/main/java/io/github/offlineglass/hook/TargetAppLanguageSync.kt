package io.github.offlineglass.hook

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/** Applies the module language from inside the target process and restores its prior app locale. */
internal object TargetAppLanguageSync {
    private const val PREFS = "liquidtab_target_language_sync_v1"
    private const val KEY_ORIGINAL = "original_app_locales"
    private const val KEY_LAST_APPLIED = "last_applied_app_locales"
    private const val ENGLISH_TAGS = "en-US"

    fun apply(context: Context, packageName: String, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33 || context.packageName != packageName) return
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        val state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = localeManager.applicationLocales.toLanguageTags()

        if (enabled) {
            if (!state.contains(KEY_ORIGINAL)) {
                // Persist before changing the system setting so a process restart
                // cannot lose the user's original per-app language.
                if (!state.edit().putString(KEY_ORIGINAL, current)
                        .putString(KEY_LAST_APPLIED, ENGLISH_TAGS).commit()) return
            } else if (state.getString(KEY_LAST_APPLIED, null) != ENGLISH_TAGS) {
                state.edit().putString(KEY_LAST_APPLIED, ENGLISH_TAGS).commit()
            }
            if (current != ENGLISH_TAGS) {
                localeManager.applicationLocales = LocaleList.forLanguageTags(ENGLISH_TAGS)
            }
            return
        }

        if (!state.contains(KEY_ORIGINAL)) return
        val original = state.getString(KEY_ORIGINAL, "").orEmpty()
        val lastApplied = state.getString(KEY_LAST_APPLIED, ENGLISH_TAGS)
        // Do not overwrite a locale the user changed independently while the
        // module's English override was active.
        if (current == lastApplied) {
            localeManager.applicationLocales = LocaleList.forLanguageTags(original)
        }
        state.edit().clear().commit()
    }
}
