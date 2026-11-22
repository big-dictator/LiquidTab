package io.github.offlineglass.hook.adapters.chatgpt

import android.content.Context
import java.io.File

/** Bounded adapter-only diagnostics; contains no conversation text or image data. */
internal object ChatGptDiagnostics {
    private val states = linkedMapOf<String, String>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scheduled = false
    @Synchronized fun report(context: Context, stage: String, status: String) {
        if (states[stage] == status) return
        states[stage] = status
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
        scheduled = false
        runCatching {
            val directory = context.getExternalFilesDir(null) ?: return@runCatching
            File(directory, "liquidtab-chatgpt-status.txt").writeText(
                states.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            )
        }
        }, 500L)
    }
}
