package io.github.offlineglass.hook.adapters.chatgpt

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View

/** Only roots owned by the same ChatGPT activity, including native Compose popup windows. */
internal object ChatGptWindows {
    fun activity(context: Context): Activity? {
        var current: Context = context
        repeat(20) {
            if (current is Activity) return current as Activity
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
    fun roots(activity: Activity): List<View> {
        val decor = activity.window?.decorView ?: return emptyList()
        val extra = runCatching {
            val type = Class.forName("android.view.WindowManagerGlobal")
            val manager = type.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)!!
            val views = type.getDeclaredField("mViews").apply { isAccessible=true }.get(manager) as List<*>
            views.filterIsInstance<View>().filter { it.isShown && activity(it.context) === activity }
        }.getOrDefault(emptyList())
        return (listOf(decor) + extra).distinct()
    }
    fun sameActivity(a: View?, b: View): Boolean {
        val activity = a?.let { activity(it.context) } ?: return false
        return activity(b.context) === activity
    }
}
