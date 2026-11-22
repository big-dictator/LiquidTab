package io.github.offlineglass.hook.adapters.weibo

import android.content.Context
import java.io.File

/** Responds only to a pre-created request; no user content or automatic files. */
internal object WeiboHomeTopProbe {
    private var last = ""
    private var count = 0
    fun record(context: Context, status: String) {
        if (status == last || count >= 24) return
        last = status
        runCatching {
            val request = File(context.getExternalFilesDir(null), "liquidtab-weibo-home-top-probe.txt")
            if (request.isFile) { request.appendText("\n$status"); count++ }
        }
    }
}
