package io.github.offlineglass.hook.adapters.weibo

import android.view.View
import android.view.ViewGroup
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.TabHost
import io.github.offlineglass.hook.GlassHostLayout

/** Resolves Weibo's live controller after its navigation hierarchy is rebuilt. */
internal fun GlassHostLayout.findWeiboTabHost(): TabHost? {
    // All native maintabs variants use TabHost, but assign different root IDs.
    // Bind through the verified native row's ancestry, never a guessed root ID.
    val source = navigationSource?.takeIf { it.isAttachedToWindow } ?: return null
    val matches = ArrayList<TabHost>()
    var parent = source.parent
    var depth = 0
    while (parent != null && depth++ < 12) {
        if (parent is TabHost && parent.tabContentView != null && parent.tabWidget != null)
            matches += parent
        parent = parent.parent
    }
    return matches.singleOrNull()
}

private fun Context.unwrapActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
