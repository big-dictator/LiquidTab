package io.github.offlineglass.hook.adapters.weibo

import android.content.Context
import java.io.File

/** Only services an explicitly pre-created diagnostic request; never creates a file. */
internal object WeiboMessageProbe {
    private var writes = 0
    fun record(context: Context, status: String) {
        if (writes >= 32 || writes >= 16 && !status.startsWith("applied")) return
        runCatching {
            val folder = context.getExternalFilesDir(null) ?: return
            val request = File(folder, "liquidtab-weibo-message-probe.txt")
            if (request.isFile) {
                request.appendText("\n$status")
                writes++
            }
        }
    }
}
