package io.github.offlineglass.hook.adapters.taobao

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Message widgets create TRecyclerView with viewCenterSectionList in Taobao 10.66.30. */
internal class TaobaoMessageInsets {
    private data class Original(var bottom: Int, val clip: Boolean, var applied: Int)
    private val originals = WeakHashMap<ViewGroup, Original>()
    private var list = WeakReference<ViewGroup>(null)
    private var lastProbe = 0L
    private var listId = 0
    private var wasMessage = false
    private val barLocation=IntArray(2)
    private val listLocation=IntArray(2)

    fun update(host: View, selected: Int) {
        if (selected != 2 || !host.isAttachedToWindow) {
            if (wasMessage) dispose()
            return
        }
        if (!wasMessage) (host as? GlassHostLayout)?.adapterCancelScrollStopHide()
        wasMessage = true
        val root = host.rootView as? ViewGroup ?: return
        var resolved = list.get()?.takeIf { it.isAttachedToWindow && it.isShown && it.rootView === root }
        if (resolved == null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastProbe < 400L) return
            lastProbe = now
            if (listId == 0) listId = host.resources.getIdentifier("viewCenterSectionList", "id", host.context.packageName)
            if (listId == 0) return
            val stack = ArrayDeque<View>()
            stack.add(root)
            var visited = 0
            var largest = 0L
            while (stack.isNotEmpty() && visited++ < 1000) {
                val view = stack.removeLast()
                if (!view.isShown) continue
                if (view is ViewGroup && view.id == listId &&
                    view.javaClass.name.endsWith("TRecyclerView") &&
                    view.width >= root.width * .75f && view.height >= root.height * .35f) {
                    val area = view.width.toLong() * view.height
                    if (area > largest) { largest = area; resolved = view }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
            list = WeakReference(resolved)
        }
        val target = resolved ?: return
        if (target.width <= 0 || target.height <= 0) return
        val original = originals.getOrPut(target) {
            Original(target.paddingBottom, target.clipToPadding, target.paddingBottom)
        }
        if (target.paddingBottom != original.applied) original.bottom = target.paddingBottom
        host.getLocationOnScreen(barLocation)
        target.getLocationOnScreen(listLocation)
        val restingTop = barLocation[1] - host.translationY.roundToInt()
        val gap = (8f * host.resources.displayMetrics.density).roundToInt()
        val padding = maxOf(original.bottom, listLocation[1] + target.height - restingTop + gap)
            .coerceAtMost(target.height)
        if (target.paddingBottom != padding) {
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, padding)
        }
        original.applied = padding
        if (target.clipToPadding) target.clipToPadding = false
    }

    fun dispose() {
        originals.forEach { (view, original) ->
            if (view.paddingBottom == original.applied)
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, original.bottom)
            if (!view.clipToPadding && original.clip) view.clipToPadding = true
        }
        originals.clear()
        list.clear()
        lastProbe = 0L
        wasMessage = false
    }
}
