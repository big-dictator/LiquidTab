package io.github.offlineglass.hook;

/** Shared exported settings, not an app-specific opt-in or an empty defaults map. */
final class StartupPreferencesPolicy {
    static boolean usable(boolean hasRevision, long revision, boolean hasGlobalGlassDecision) {
        return hasRevision && revision >= 0L && hasGlobalGlassDecision;
    }
}
