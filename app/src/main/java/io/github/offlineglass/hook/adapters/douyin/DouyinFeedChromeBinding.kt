package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.widget.FrameLayout
import java.lang.reflect.Method

/** One bounded reflection result per actual native class; no APK symbol lists. */
internal object DouyinFeedChromeBinding {
    private data class Contract(val bottom: Method?)
    private val contracts = object : ClassValue<Contract>() {
        override fun computeValue(type: Class<*>): Contract {
            val model = runCatching {
                Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, type.classLoader)
            }.getOrNull() ?: return Contract(null)
            return Contract(DouyinFeedChromeContract.resolve(type, FrameLayout::class.java, View::class.java, model))
        }
    }

    fun matches(view: View): Boolean {
        if (view !is FrameLayout) return false
        val getter = contracts.get(view.javaClass).bottom ?: return false
        val bottom = runCatching { getter.invoke(view) as? View }.getOrNull() ?: return false
        // A lookalike API returning some other page's controls is not a binding.
        var parent = bottom.parent
        var depth = 0
        while (parent is View && depth++ < 32) {
            if (parent === view) return true
            parent = parent.parent
        }
        return false
    }
}
