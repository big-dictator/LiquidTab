package io.github.offlineglass.util

import android.util.Log
import io.github.offlineglass.BuildConfig

/**
 * Single switch for diagnostic logging and per-frame instrumentation.
 *
 * The injected hook runs inside target applications, so anything logged here
 * ends up in those apps' logcat. Release builds stay silent by default; a
 * future settings toggle can call [setEnabled] to opt in at runtime without
 * rebuilding the module.
 */
internal object GlassLog {
    @Volatile
    var enabled: Boolean = BuildConfig.DEBUG
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (enabled) Log.w(tag, message, error)
    }

    /** Errors are never hot-path instrumentation; always surface them. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
    }
}
