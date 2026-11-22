package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import java.util.WeakHashMap

/** Some OEM freeform windows do not update Activity's multi-window boolean. */
internal object BilibiliDetailWindowMode {
    private data class Key(val w: Int, val h: Int, val orientation: Int, val rootW: Int, val rootH: Int,
        val multi: Boolean, val pip: Boolean)
    private val cached = WeakHashMap<Activity, Pair<Key, Boolean>>()
    fun invalidate(activity: Activity) { cached.remove(activity) }
    fun windowed(activity: Activity): Boolean {
        val config = activity.resources.configuration
        val root = activity.window.decorView
        val key = Key(config.screenWidthDp, config.screenHeightDp, config.orientation,
            root.width, root.height, activity.isInMultiWindowMode, activity.isInPictureInPictureMode)
        cached[activity]?.takeIf { it.first == key }?.let { return it.second }
        val value = key.multi || key.pip || runCatching {
            activity.windowManager.currentWindowMetrics.bounds != activity.windowManager.maximumWindowMetrics.bounds
        }.getOrDefault(false)
        cached[activity] = key to value
        return value
    }
}
