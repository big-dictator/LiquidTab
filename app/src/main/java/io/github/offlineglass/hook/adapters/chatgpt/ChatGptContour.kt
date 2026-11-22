package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.Path
import android.graphics.RectF
import io.github.offlineglass.hook.setBottomBarSquircle

/** The same inward continuous corner path used by XHS glass buttons. */
internal class ChatGptContour {
    fun path(path: Path, width: Float, height: Float, radius: Float) {
        path.setBottomBarSquircle(RectF(0f, 0f, width, height), radius, -1f)
    }
}
