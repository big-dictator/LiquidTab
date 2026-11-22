package io.github.offlineglass.hook.adapters.tiktok

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Navigation capability is independent of version-sensitive player factories. */
internal object TikTokNativeNavigation {
    private val schemas = HashMap<Class<*>, Boolean>()
    private val rows = WeakHashMap<View, WeakReference<ViewGroup>>()
    private val position = IntArray(2)

    private fun cellSchema(type: Class<*>): Boolean = schemas.getOrPut(type) {
        runCatching {
            ImageView::class.java.isAssignableFrom(type.getMethod("getTabIcon").returnType) &&
                TextView::class.java.isAssignableFrom(type.getMethod("getTabTitleView").returnType) &&
                type.getMethod("getIconTabLogic").returnType != Void.TYPE &&
                ImageView::class.java.isAssignableFrom(type.getMethod("getRedDotVIew").returnType)
        }.getOrDefault(false)
    }

    fun find(root: View): ViewGroup? {
        rows[root]?.get()?.takeIf { it.isAttachedToWindow && it.isShown && it.rootView === root && it.childCount == 5 }
            ?.let { return it }
        val row = AdapterNavigationSearch.findFirst(root) { candidate ->
            if (!candidate.isAttachedToWindow || !candidate.isShown || candidate.childCount != 5 ||
                candidate.width < root.width * .8f || candidate.height <= 0 || candidate.height > root.height * .2f)
                return@findFirst false
            candidate.getLocationInWindow(position)
            if (position[1] < root.height * .65f) return@findFirst false
            val type = candidate.getChildAt(0).javaClass
            (1 until 5).all { candidate.getChildAt(it).javaClass === type } && cellSchema(type)
        }
        if (row != null) rows[root] = WeakReference(row)
        return row
    }
}
