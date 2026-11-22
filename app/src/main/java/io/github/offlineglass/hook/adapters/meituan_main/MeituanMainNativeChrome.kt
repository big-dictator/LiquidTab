package io.github.offlineglass.hook.adapters.meituan_main

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AppNativeChromeController
import kotlin.math.abs

/** Removes only Meituan main's native shell while preserving its live click targets. */
internal class MeituanMainNativeChrome : AppNativeChromeController {
    private data class Visual(val alpha: Float, val background: android.graphics.drawable.Drawable?)
    private val originals = java.util.WeakHashMap<View, Visual>()
    private data class Layout(val view: View, val height: Int, val bottom: Int, var appliedHeight: Int, var appliedBottom: Int)
    private val layouts = ArrayList<Layout>()
    private var bound: ViewGroup? = null
    private fun remember(view: View, source: Boolean = false) {
        originals.getOrPut(view) { Visual(if (source) 1f else view.alpha, view.background) }
    }
    private fun restore() {
        originals.forEach { (view, visual) ->
            if (view.alpha == 0f) view.alpha = visual.alpha
            if (view.background == null) view.background = visual.background
        }
        originals.clear()
        for (saved in layouts) (saved.view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            if (it.height == saved.appliedHeight) it.height = saved.height
            if (it.bottomMargin == saved.appliedBottom) it.bottomMargin = saved.bottom
            saved.view.layoutParams = it
        }
        layouts.clear(); bound = null
    }
    override fun update(host: View, source: ViewGroup?) {
        source ?: return
        if (!MeituanMainNativeTabs.activated(source)) return
        val block = source.parent as? ViewGroup ?: return
        if (MeituanMainNativeTabs.row(block) !== source) return
        if (bound !== source) {
            restore(); bound = source
            MeituanMainNativeTabs.onRestore(source, ::restore)
        }
        remember(source, source = true); remember(block)
        source.background = null
        block.background = null
        for (index in 0 until block.childCount) {
            val layer = block.getChildAt(index)
            if (layer === source) continue
            remember(layer)
            layer.background = null
            if (layer.alpha != 0f) layer.alpha = 0f
        }

        val shell = block.parent as? ViewGroup ?: return
        val nativeContentHeight = (0 until source.childCount)
            .map(source::getChildAt)
            .filter { it.visibility == View.VISIBLE }
            .maxOfOrNull { it.height }
            ?.coerceAtLeast(1) ?: return
        for (content in listOfNotNull(MeituanMainNativeTabs.content(source))) {
            val idName = runCatching {
                if (content.id == View.NO_ID) null else content.resources.getResourceEntryName(content.id)
            }.getOrNull()
            if (idName != "main") continue
            val params = content.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val previousHeight = params.height
            val previousBottom = params.bottomMargin
            var changed = false
            if (params.bottomMargin in 1..block.height.coerceAtLeast(1)) {
                params.bottomMargin = 0
                changed = true
            }
            if (params.height > 0 && abs(shell.height - content.height - nativeContentHeight) <= 2) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (changed) {
                val saved = layouts.singleOrNull { it.view === content }
                if (saved == null) layouts += Layout(content, previousHeight, previousBottom, params.height, params.bottomMargin)
                else { saved.appliedHeight = params.height; saved.appliedBottom = params.bottomMargin }
                content.layoutParams = params
                content.requestLayout()
                shell.requestLayout()
            }
            return
        }
    }
}
