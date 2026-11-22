package io.github.offlineglass.hook.adapters.douyin

import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout

/** Expand only the verified feed pager's viewport ancestors, never its controls/player texture. */
internal object DouyinFeedViewport {
    private val viewportLayers = java.util.WeakHashMap<View, Boolean>()
    private data class PagerApi(val current: java.lang.reflect.Method?, val adapter: java.lang.reflect.Method?)
    private val apis = object : ClassValue<PagerApi>() {
        override fun computeValue(type: Class<*>): PagerApi = runCatching {
            val current = type.getMethod("getCurrentItem")
            val adapter = type.getMethod("getAdapter")
            require(current.returnType == Int::class.javaPrimitiveType && !adapter.returnType.isPrimitive)
            PagerApi(current, adapter)
        }.getOrDefault(PagerApi(null, null))
    }
    private class Chain(val layers: List<java.lang.ref.WeakReference<View>>, val boundary: java.lang.ref.WeakReference<View>)
    private val chains = java.util.WeakHashMap<ViewGroup, Chain>()
    fun owns(view: View): Boolean = viewportLayers.containsKey(view)
    fun fillFromPage(page: View, release: (View) -> Unit) {
        if (page.javaClass.name != "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView") return
        val pager = page.parent as? ViewGroup ?: return
        chains[pager]?.let { cached ->
            var node: View? = pager
            var valid = true
            for (ref in cached.layers) {
                val layer = ref.get()
                if (layer == null || node !== layer || !layer.isAttachedToWindow) { valid = false; break }
                node = layer.parent as? View
            }
            if (valid && node != null && node === cached.boundary.get()) {
                for (ref in cached.layers) ref.get()?.let { fill(it, release) }
                return
            }
            chains.remove(pager)
        }
        // Native pager capabilities + actual native holder ownership confirm this chain.
        val api = apis.get(pager.javaClass)
        if (api.current == null || runCatching { api.adapter?.invoke(pager) != null }.getOrDefault(false).not()) return
        val chain = ArrayList<View>()
        var node: View? = pager
        repeat(12) {
            val current = node ?: return@repeat
            if (current.javaClass.name == "com.ss.android.ugc.aweme.feed.ui.LayoutCheckFrameLayout") {
                if (chain.none { it.javaClass.name == "com.ss.android.ugc.aweme.feed.ui.LayoutCheckRefreshLayout" }) return
                chains[pager] = Chain(chain.map { java.lang.ref.WeakReference(it) }, java.lang.ref.WeakReference(current))
                chain.forEach { fill(it, release) }
                return
            }
            chain.add(current)
            node = current.parent as? View
        }
    }
    private fun fill(layer: View, release: (View) -> Unit) {
        viewportLayers[layer] = true
        release(layer)
        val lp = layer.layoutParams ?: return
        var changed = false
        if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) { lp.height = -1; changed = true }
        if (lp is ViewGroup.MarginLayoutParams && lp.bottomMargin != 0) { lp.bottomMargin = 0; changed = true }
        if (lp is RelativeLayout.LayoutParams && lp.getRule(RelativeLayout.ABOVE) != 0) {
            lp.removeRule(RelativeLayout.ABOVE); changed = true
        }
        if (changed) layer.layoutParams = lp
        if (layer is ViewGroup && layer.paddingBottom != 0)
            layer.setPadding(layer.paddingLeft, layer.paddingTop, layer.paddingRight, 0)
    }
}
