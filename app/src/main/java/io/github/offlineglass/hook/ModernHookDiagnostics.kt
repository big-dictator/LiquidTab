package io.github.offlineglass.hook

import android.content.Context
import io.github.offlineglass.hook.compat.XposedBridge
import java.io.File

/** Per-process evidence, independent of provider access and filtered device logs. */
internal object ModernHookDiagnostics {
    fun report(context: Context) {
        runCatching {
            val framework = XposedBridge.framework()
            val settings = framework.getRemotePreferences("offline_glass")
            val report = "pid=${android.os.Process.myPid()} api=${framework.apiVersion} " +
                "framework=${framework.frameworkName} version=${framework.frameworkVersion} " +
                "revision=${settings.getLong("revision", -1L)} keys=${settings.all.size}\n"
            File(context.getExternalFilesDir(null), "modern_framework_status.txt").writeText(report)
        }.onFailure { android.util.Log.w("LiquidTabModern", "Diagnostic unavailable", it) }
    }
}
