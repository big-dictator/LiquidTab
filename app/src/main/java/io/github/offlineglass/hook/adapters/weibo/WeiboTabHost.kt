package io.github.offlineglass.hook.adapters.weibo

import android.view.View
import android.view.ViewGroup
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.github.offlineglass.hook.GlassHostLayout

/** Resolves Weibo's live controller after its navigation hierarchy is rebuilt. */
internal fun GlassHostLayout.findWeiboTabHost(): ViewGroup? {
    val activity = context.unwrapActivity() ?: return null
    val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
    val stack = ArrayDeque<View>(); stack += content
    var mainRadio: View? = null
    while (stack.isNotEmpty()) {
        val view = stack.removeLast()
        val name = viewResourceEntryName(view).orEmpty()
        if (name == "main_radio" || name == "main_theme_radio") {
            mainRadio = view; break
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) stack += view.getChildAt(index)
    }
    if (mainRadio == null) {
        val source = navigationSource?.takeIf { it.isAttachedToWindow } ?: return null
        return (source.parent as? ViewGroup)?.parent as? ViewGroup
    }
    return (mainRadio.parent as? ViewGroup)?.parent as? ViewGroup
}

private fun Context.unwrapActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
